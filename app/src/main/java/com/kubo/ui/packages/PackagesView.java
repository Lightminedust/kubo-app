package com.kubo.ui.packages;

import com.kubo.command.CommandEntry;
import com.kubo.model.packages.PackageEntry;
import com.kubo.model.packages.PackageRole;
import com.kubo.model.packages.PackageInventory;
import com.kubo.packages.AptCommands;
import com.kubo.packages.AptService;
import com.kubo.privilege.RootSession;
import com.kubo.ui.PromptCard;
import com.kubo.ui.docker.SudoPrompt;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What is installed on the server and what to do about it: the map in the middle, whatever you opened on it
 * listed on the right, and the actions that change things — each one shown as a simulation before it runs.
 */
public final class PackagesView extends StackPane {

    private static final Color ALERT = PackagePalette.ALERT;
    private static final Color AMBER = PackagePalette.AMBER;
    private static final Color LIVE = PackagePalette.LIVE;

    private final AptService apt;
    /** What apt is doing right now, or null. One lock on the machine means one job at a time. */
    private String running;
    private final Label workingChip = new Label();
    /** Only to restart what needrestart names. Everything else about services is in SERVICES.SCOPE. */
    private final com.kubo.services.ServiceControl services;
    private final RootSession root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-packages");
        thread.setDaemon(true);
        return thread;
    });

    private final PackageTree tree = new PackageTree();
    private final Label headline = label("PACKAGES", "sheet-title");
    private final Label caption = label("", "sheet-caption");
    private final HBox tagRow = new HBox(6);
    private final Label installedValue = label("—", "readout-value");
    private final Label waitingValue = label("—", "readout-value");
    private final Label securityValue = label("—", "readout-value");

    private final Label listTitle = label("EVERYTHING", "series");
    private final Label listNote = label("", "metric-label");
    private final Label listCount = label("", "metric-label");
    private final ToggleButton askedForOnly = new ToggleButton("ASKED FOR");
    /**
     * The three questions somebody actually arrives with, as buttons.
     *
     * <p>A thousand packages in one list answers none of them. What has a security fix waiting, what can be
     * thrown away, and what did I ask for as opposed to what came in behind it, are separate errands.
     */
    private final ToggleButton securityOnly = new ToggleButton("SECURITY ONLY");
    private final ToggleButton orphansOnly = new ToggleButton("NO LONGER NEEDED");
    private final TextField filterField = new TextField();
    private final ListView<PackageEntry> list = new ListView<>();
    private final javafx.collections.ObservableList<PackageEntry> shown =
            javafx.collections.FXCollections.observableArrayList();
    private final TextField installField = new TextField();
    private final Label status = label("", "card-note");
    private final VBox notesBox = new VBox(4);

    private PackageInventory inventory = PackageInventory.empty();
    private PackageRole role;
    private String section;
    private StackPane overlay;

    /**
     * @param services restarts a unit that is running on a library that has been replaced; null when this
     *                 screen was built without one, in which case the card only reads
     */
    public PackagesView(AptService apt, RootSession root, com.kubo.services.ServiceControl services) {
        this.services = services;
        this.apt = apt;
        this.root = root;
        getStyleClass().add("scope-view");

        tree.setOnOpen((opened, chosen) -> {
            role = opened;
            section = chosen;
            refreshList();
        });
        tree.setOnPackage(chosen -> {
            list.getSelectionModel().select(chosen);
            list.scrollTo(chosen);
        });

        BorderPane body = new BorderPane(tree);
        body.setRight(buildPanel());
        BorderPane layout = new BorderPane(body);
        layout.setTop(buildMasthead());
        getChildren().add(layout);
    }

    /**
     * Builds the screen without a live service, for previews.
     */
    PackagesView() {
        this(null, null, null);
    }

    public void start() {
        refresh();
    }

    public void shutdown() {
        tree.stop();
        worker.shutdownNow();
    }

    // Reading

    private void refresh() {
        if (apt == null) {
            return;
        }
        tree.setLoading(true);
        worker.execute(() -> {
            PackageInventory read = apt.read();
            Platform.runLater(() -> {
                tree.setLoading(false);
                apply(read);
            });
        });
    }

    private void apply(PackageInventory read) {
        this.inventory = read;
        tree.setInventory(read);

        long waiting = read.upgradable();
        long security = read.security();
        headline.setText(security > 0 ? "SECURITY UPDATES" : waiting > 0 ? "UPDATES WAITING" : "UP TO DATE");
        headline.setStyle("-fx-text-fill: " + web(security > 0 ? ALERT : waiting > 0 ? AMBER : LIVE) + ";");
        long branches = read.packages().stream().map(PackageEntry::role).distinct().count();
        caption.setText((read.status().usable()
                ? "APT " + read.status().version() + "   ·   " + branches + " BRANCHES   ·   "
                        + read.manual() + " ASKED FOR, " + (read.total() - read.manual()) + " DEPENDENCIES"
                : "THIS SERVER DOES NOT USE APT").toUpperCase(Locale.ROOT));
        if (!read.status().usable()) {
            // Naming what is actually there, rather than leaving somebody to wonder whether Kubo is confused
            // or their server is unusual.
            nameTheManager();
        }

        installedValue.setText(String.valueOf(read.total()));
        waitingValue.setText(String.valueOf(waiting));
        waitingValue.setStyle("-fx-text-fill: " + web(waiting > 0 ? AMBER : LIVE) + ";");
        securityValue.setText(String.valueOf(security));
        securityValue.setStyle("-fx-text-fill: " + web(security > 0 ? ALERT : LIVE) + ";");

        tagRow.getChildren().setAll(
                tag(bytes(read.bytes()) + " ON DISK"),
                tag(read.autoRemovable() + " NO LONGER NEEDED"),
                read.status().rebootRequired()
                        ? restartTag()
                        : tag("NO RESTART NEEDED"));

        notesBox.getChildren().clear();
        for (String note : read.notes()) {
            Label line = label("▸ " + note, "card-note");
            line.setWrapText(true);
            notesBox.getChildren().add(line);
        }
        notesBox.setVisible(!read.notes().isEmpty());
        notesBox.setManaged(!read.notes().isEmpty());
        refreshList();
    }

    /**
     * The right-hand list: the chosen family, or everything, narrowed by whatever is typed.
     */
    private void refreshList() {
        List<PackageEntry> source = new ArrayList<>();
        if (role == null) {
            source.addAll(inventory.waiting());
            listTitle.setText("WAITING FOR AN UPDATE");
            listNote.setText("OPEN A BRANCH ON THE TREE TO SEE WHAT IS IN IT");
            if (source.isEmpty()) {
                source.addAll(inventory.packages());
                listTitle.setText("EVERYTHING INSTALLED");
            }
        } else if (section == null) {
            inventory.packages().stream().filter(entry -> entry.role() == role).forEach(source::add);
            listTitle.setText(role.title());
            listNote.setText(role.note().toUpperCase(Locale.ROOT));
        } else {
            inventory.packages().stream()
                    .filter(entry -> entry.role() == role && entry.family().equals(section))
                    .forEach(source::add);
            listTitle.setText(role.title() + "  ·  " + section.toUpperCase(Locale.ROOT));
            listNote.setText("SECTION " + section.toUpperCase(Locale.ROOT) + " OF " + role.title());
        }
        // Each filter narrows what the branch or the search already chose, rather than replacing it.
        if (securityOnly.isSelected()) {
            source.removeIf(entry -> !entry.security());
            listTitle.setText("SECURITY UPDATES WAITING");
            listNote.setText("ONLY THE ONES WITH A SECURITY FIX WAITING");
        }
        if (orphansOnly.isSelected()) {
            source.removeIf(entry -> !entry.autoRemovable());
            listTitle.setText("NO LONGER NEEDED");
            listNote.setText("PULLED IN AS DEPENDENCIES, AND NOTHING DEPENDS ON THEM ANY MORE");
        }
        if (askedForOnly.isSelected()) {
            source.removeIf(entry -> !entry.manual());
            listNote.setText(listNote.getText() + "  ·  DEPENDENCIES HIDDEN");
        }
        source.sort(java.util.Comparator.comparing(PackageEntry::manual).reversed()
                .thenComparing(java.util.Comparator.comparingLong(PackageEntry::installedKb).reversed())
                .thenComparing(PackageEntry::name));
        String filter = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);
        List<PackageEntry> matching = filter.isEmpty() ? source
                : source.stream().filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(filter)).toList();
        shown.setAll(matching);
        listCount.setText(String.valueOf(matching.size()));
    }

    // Actions

    /**
     * Opens the card for one apt job: what it will do, then what it is doing.
     *
     * @param confirm the word to type first, or null to start the moment it opens
     * @param work    the streaming call on the service
     */
    private void showWork(String title, String what, javafx.scene.Node before, String confirm, boolean danger,
                          java.util.function.Function<java.util.function.Consumer<String>, CommandEntry> work) {
        if (apt == null) {
            return;
        }
        if (running != null) {
            // Apt takes one lock for the whole machine. A second one started while the first is going does not
            // fail, it waits, silently, for as long as the first takes. That is the minute and a half of
            // apparently nothing that this refusal exists to prevent.
            report("APT IS ALREADY " + running.toUpperCase(Locale.ROOT)
                    + ". WAIT FOR IT: TWO AT ONCE JUST QUEUE BEHIND EACH OTHER.", true);
            return;
        }
        showOverlay(new AptWorkCard(title, what, before, confirm, danger,
                (onLine, done) -> runWithRoot(() -> {
                    running = title.toLowerCase(Locale.ROOT);
                    sayWorking();
                    worker.execute(() -> {
                        CommandEntry result = work.apply(onLine);
                        Platform.runLater(() -> {
                            running = null;
                            sayWorking();
                            done.accept(result.success() ? null
                                    : firstLine(result.error().isBlank() ? result.output() : result.error()));
                            report(result.success() ? title + " DONE"
                                    : firstLine(result.error().isBlank() ? result.output() : result.error()),
                                    !result.success());
                            refresh();
                        });
                    });
                }), this::hideOverlay));
    }

    /** Says in the rail what apt is doing, so closing the card does not lose the thread. */
    private void sayWorking() {
        workingChip.setText(running == null ? "" : "APT IS " + running.toUpperCase(Locale.ROOT) + "…");
        workingChip.setVisible(running != null);
        workingChip.setManaged(running != null);
    }

    /**
     * Where apt fetches from, and the keys it checks signatures against.
     *
     * <p>Both readings in one go: they are the same subject from two ends, and an expired key is only
     * meaningful next to the repository it belongs to.
     */
    private void showSources() {
        if (apt == null) {
            return;
        }
        report("READING THE SOURCES…", false);
        runWithRoot(() -> worker.execute(() -> {
            java.util.List<com.kubo.model.packages.AptSource> sources = apt.sources();
            java.util.List<com.kubo.model.packages.SigningKey> keys = apt.keys();
            long cache = apt.cacheBytes();
            Platform.runLater(() -> {
                status.setText("");
                showOverlay(new SourcesCard(sources, keys, java.time.LocalDate.now(), cache,
                        sourcesHands(), this::hideOverlay));
            });
        }));
    }

    private SourcesCard.Hands sourcesHands() {
        return new SourcesCard.Hands() {
            @Override
            public void switchSource(com.kubo.model.packages.AptSource source, boolean enable,
                                     java.util.function.Consumer<String> done) {
                worker.execute(() -> {
                    CommandEntry result = apt.switchSource(source.file(), source.line(), enable);
                    Platform.runLater(() -> done.accept(result.success() ? null
                            : firstLine(result.error().isBlank() ? result.output() : result.error())));
                });
            }

            @Override
            public void addSource(String name, String uri, String suite, String components, String signedBy,
                                  java.util.function.Consumer<String> done) {
                // Checked here first so a typo answers straight away rather than after a round trip.
                try {
                    AptCommands.addSource(name);
                    AptCommands.sourceLine(uri, suite, components, signedBy);
                } catch (IllegalArgumentException wrong) {
                    done.accept(wrong.getMessage());
                    return;
                }
                runWithRoot(() -> worker.execute(() -> {
                    CommandEntry result = apt.addSource(name, uri, suite, components, signedBy);
                    Platform.runLater(() -> {
                        done.accept(result.success() ? null
                                : firstLine(result.error().isBlank() ? result.output() : result.error()));
                        if (result.success()) {
                            refresh();
                        }
                    });
                }));
            }
        };
    }

    /** Only for the previews: the sources and keys as they were read. */
    void showSourcesForPreview(java.util.List<com.kubo.model.packages.AptSource> sources,
                               java.util.List<com.kubo.model.packages.SigningKey> keys,
                               java.time.LocalDate today) {
        showOverlay(new SourcesCard(sources, keys, today, 412_000_000L,
                (source, enable, done) -> { }, () -> { }));
    }

    /**
     * The RESTART REQUIRED chip, made into something that answers the question it raises.
     *
     * <p>It used to say a restart was due and stop there, which reads as "reboot this server". Clicking it now
     * asks needrestart which services are actually affected, and almost always the answer is two of them
     * rather than the whole machine.
     */
    private javafx.scene.Node restartTag() {
        Button chip = new Button("RESTART REQUIRED  ·  WHAT EXACTLY?");
        chip.getStyleClass().addAll("rail-button", "compact-button");
        chip.setFocusTraversable(false);
        chip.setOnAction(event -> showRestartNeeds());
        return chip;
    }

    /** Which services are running on libraries that have been replaced, and a button each. */
    private void showRestartNeeds() {
        if (apt == null) {
            return;
        }
        report("ASKING WHICH SERVICES ARE AFFECTED…", false);
        runWithRoot(() -> worker.execute(() -> {
            com.kubo.model.packages.RestartNeeds needs = apt.restartNeeds();
            Platform.runLater(() -> {
                status.setText("");
                showOverlay(new RestartCard(needs, new RestartCard.Hands() {
                    @Override
                    public void restart(String unit, java.util.function.Consumer<String> done) {
                        if (services == null) {
                            done.accept("This screen was opened without a way to restart services.");
                            return;
                        }
                        worker.execute(() -> {
                            CommandEntry result = services.run(com.kubo.model.services.Action.RESTART, unit);
                            Platform.runLater(() -> done.accept(result.success() ? null
                                    : firstLine(result.error().isBlank() ? result.output() : result.error())));
                        });
                    }

                    @Override
                    public void reboot(java.util.function.Consumer<String> done) {
                        if (apt == null) {
                            done.accept("This screen was opened without a connection.");
                            return;
                        }
                        worker.execute(() -> {
                            CommandEntry result = apt.reboot();
                            // A reboot cuts the connection while it answers, so a command that did not come
                            // back is the expected outcome rather than a failure worth reporting.
                            Platform.runLater(() -> done.accept(result.success() || !result.ran() ? null
                                    : firstLine(result.error().isBlank() ? result.output() : result.error())));
                        });
                    }
                }, this::hideOverlay));
            });
        }));
    }

    /**
     * Asks which manager this machine has and says so under the headline.
     *
     * <p>Only when there is no apt, and only once per reading: it is a cheap command but there is no reason to
     * send it to a machine whose answer is already on the screen.
     */
    private void nameTheManager() {
        if (apt == null) {
            return;
        }
        worker.execute(() -> {
            com.kubo.model.tools.PackageManager manager = apt.manager();
            Platform.runLater(() -> {
                if (manager == com.kubo.model.tools.PackageManager.NONE) {
                    report("KUBO FOUND NO PACKAGE MANAGER IT KNOWS ON THIS MACHINE.", true);
                    return;
                }
                caption.setText(("THIS SERVER USES " + manager.name() + "   ·   " + manager.family()
                        + "   ·   KUBO READS APT ONLY").toUpperCase(Locale.ROOT));
                report("Kubo reads apt in full and knows " + manager.name() + " well enough to install tools "
                        + "with it, but not to show you its packages. Everything else on this server works.",
                        false);
            });
        });
    }

    /**
     * What apt said it would do, laid out: the warning first when there is one, then what goes and what comes.
     */
    private static javafx.scene.Node planBody(com.kubo.model.packages.PackagePlan plan) {
        VBox body = new VBox(10);
        if (!plan.warning().isEmpty()) {
            Label warning = label(plan.warning(), "card-problem");
            warning.setWrapText(true);
            warning.setMaxWidth(760);
            warning.setMinHeight(Region.USE_PREF_SIZE);
            VBox box = new VBox(6, label("THIS TAKES SOMETHING YOU CANNOT PUT BACK", "series"), warning);
            box.getStyleClass().add("lockout-box");
            box.setPadding(new Insets(12, 14, 12, 14));
            body.getChildren().add(box);
        }
        if (!plan.cost().isEmpty()) {
            body.getChildren().add(plainNote(plan.cost()));
        }
        names(body, "GOING", plan.removing(), "card-problem");
        names(body, "ARRIVING", plan.installing(), "card-success");
        names(body, "MOVING TO A NEWER VERSION", plan.upgrading(), "mono-muted");
        if (body.getChildren().isEmpty()) {
            body.getChildren().add(plainNote("Apt did not name anything it would change."));
        }
        return body;
    }

    private static void names(VBox into, String heading, List<String> names, String styleClass) {
        if (names.isEmpty()) {
            return;
        }
        javafx.scene.layout.FlowPane pane = new javafx.scene.layout.FlowPane(6, 6);
        for (String name : names) {
            pane.getChildren().add(label(name, styleClass));
        }
        into.getChildren().addAll(label(heading + "  ·  " + names.size(), "series"), pane);
    }

    /**
     * What a package is, read into a card instead of shown as the paragraph apt prints.
     *
     * <p>Needs no root: describing a package reads the index, which everybody may.
     */
    private void showSheet(String name) {
        if (apt == null) {
            return;
        }
        worker.execute(() -> {
            com.kubo.model.packages.PackageSheet sheet =
                    com.kubo.model.packages.PackageSheet.parse(apt.describe(name).output());
            Platform.runLater(() -> {
                if (sheet.isEmpty()) {
                    report("APT KNOWS NOTHING ABOUT " + name.toUpperCase(Locale.ROOT) + ".", true);
                    return;
                }
                showOverlay(new PackageSheetCard(sheet, sheetHands(), this::hideOverlay));
            });
        });
    }

    private PackageSheetCard.Hands sheetHands() {
        return new PackageSheetCard.Hands() {
            @Override
            public void open(String name, java.util.function.Consumer<com.kubo.model.packages.PackageSheet> done) {
                worker.execute(() -> {
                    com.kubo.model.packages.PackageSheet read =
                            com.kubo.model.packages.PackageSheet.parse(apt.describe(name).output());
                    Platform.runLater(() -> done.accept(read));
                });
            }

            @Override
            public void browse(String url) {
                // On this computer. The server has no browser, and nothing about this leaves the operator's
                // machine: the address came out of the package index, and the browser is the local one.
                try {
                    com.kubo.platform.Host.openLink(url);
                } catch (RuntimeException | java.io.IOException e) {
                    report("COULD NOT OPEN THAT LINK: " + e.getMessage(), true);
                }
            }
        };
    }

    /** Every waiting version, listed before it happens and named again by apt as it goes. */
    private void showUpgrade(boolean full) {
        List<PackageEntry> waiting = inventory.waiting();
        showWork(full ? "FULL UPGRADE" : "UPGRADE EVERYTHING",
                AptWorkCard.waitingHeadline(waiting) + (full
                        ? " A full upgrade may add packages and remove others to satisfy new dependencies."
                        : " Every waiting version is installed. Nothing is added and nothing is removed."),
                AptWorkCard.waitingList(waiting), full ? "FULL" : "UPGRADE", full,
                onLine -> full ? apt.fullUpgrade(onLine) : apt.upgrade(onLine));
    }

    /**
     * Removing what nothing depends on any more, with the list of what that is shown first.
     *
     * <p>The list comes from the reading the screen already has, so opening the card costs no command. Apt
     * names every one of them again as it goes, which is what settles any disagreement.
     */
    private void showAutoremove() {
        List<PackageEntry> orphans = inventory.packages().stream()
                .filter(PackageEntry::autoRemovable).toList();
        VBox rows = new VBox(2);
        if (orphans.isEmpty()) {
            rows.getChildren().add(plainNote("Nothing is orphaned. There is nothing for this to do."));
        } else {
            for (PackageEntry entry : orphans) {
                Label name = label(entry.name(), "log-command");
                name.setMinWidth(260);
                HBox row = new HBox(10, name, label(entry.version(), "mono-muted"));
                row.setAlignment(Pos.CENTER_LEFT);
                rows.getChildren().add(row);
            }
        }
        showWork("REMOVE WHAT IS NO LONGER NEEDED",
                orphans.isEmpty() ? "Nothing is orphaned."
                        : orphans.size() + (orphans.size() == 1 ? " package was pulled in as a dependency and "
                                + "nothing depends on it any more." : " packages were pulled in as dependencies "
                                + "and nothing depends on them any more."),
                rows, "REMOVE", true, onLine -> apt.autoremove(onLine));
    }

    /** Reading the index, watched: it downloads from every source and is where a broken one shows itself. */
    private void showIndex() {
        showWork("READING THE INDEX",
                "Apt asks every source what it now has. Nothing is installed and nothing is changed on this "
                        + "machine; only the list of what could be.",
                null, null, false, onLine -> apt.updateIndex(onLine));
    }

    /**
     * Only for the previews: the upgrade card, either as it opens or partway through.
     *
     * @param printed what apt has said so far, or null for the card before anything was confirmed
     */
    void showUpgradeForPreview(List<PackageEntry> waiting, boolean full, List<String> printed) {
        AptWorkCard card = new AptWorkCard(full ? "FULL UPGRADE" : "UPGRADE EVERYTHING",
                AptWorkCard.waitingHeadline(waiting)
                        + " Every waiting version is installed. Nothing is added and nothing is removed.",
                AptWorkCard.waitingList(waiting), full ? "FULL" : "UPGRADE", full,
                (onLine, done) -> { }, () -> { });
        if (printed != null) {
            card.showForPreview(printed, false, false);
        }
        showOverlay(card);
    }

    /** Only for the previews: what needs restarting, with one already done. */
    void showRestartForPreview(com.kubo.model.packages.RestartNeeds needs, String restarted) {
        RestartCard card = new RestartCard(needs, (unit, done) -> { }, () -> { });
        if (restarted != null) {
            card.markForPreview(restarted);
        }
        showOverlay(card);
    }

    /** Only for the previews: one package's sheet. */
    void showSheetForPreview(com.kubo.model.packages.PackageSheet sheet) {
        showOverlay(new PackageSheetCard(sheet, new PackageSheetCard.Hands() {
            @Override
            public void open(String name, java.util.function.Consumer<com.kubo.model.packages.PackageSheet> done) {
            }

            @Override
            public void browse(String url) {
            }
        }, () -> { }));
    }

    /** Only for the previews: reading the index, partway through. */
    void showIndexForPreview(List<String> printed, boolean finished, boolean worked) {
        AptWorkCard card = new AptWorkCard("READING THE INDEX",
                "Apt asks every source what it now has. Nothing is installed and nothing is changed on this "
                        + "machine; only the list of what could be.",
                null, "NEVER", false, (onLine, done) -> { }, () -> { });
        card.showForPreview(printed, finished, worked);
        showOverlay(card);
    }

    /**
     * Asks apt what a change would do, then shows that answer before letting it happen.
     */
    private void propose(String name, boolean installing) {
        if (apt == null) {
            return;
        }
        java.util.Optional<String> problem = AptCommands.nameProblem(name);
        if (problem.isPresent()) {
            report(problem.get(), true);
            return;
        }
        String clean = name.strip().toLowerCase(Locale.ROOT);
        report((installing ? "ASKING APT WHAT INSTALLING " : "ASKING APT WHAT REMOVING ") + clean + " WOULD DO…",
                false);
        worker.execute(() -> {
            com.kubo.model.packages.PackagePlan plan =
                    installing ? apt.planInstall(clean) : apt.planRemove(clean);
            Platform.runLater(() -> {
                status.setText("");
                if (plan.refused()) {
                    report(plan.problem(), true);
                    return;
                }
                showWork(installing ? "INSTALL " + clean : "REMOVE " + clean,
                        plan.summary().isBlank() ? "Apt did not say what it would do." : plan.summary(),
                        planBody(plan), clean, !installing || !plan.breaking().isEmpty(),
                        onLine -> installing ? apt.install(clean, onLine) : apt.remove(clean, onLine));
            });
        });
    }

    private void run(String label, java.util.function.Supplier<CommandEntry> work) {
        runWithRoot(() -> {
            report(label + "…", false);
            worker.execute(() -> {
                CommandEntry result = work.get();
                Platform.runLater(() -> {
                    report(result.success() ? label + " DONE"
                            : firstLine(result.error().isBlank() ? result.output() : result.error()),
                            !result.success());
                    refresh();
                });
            });
        });
    }

    private void report(String text, boolean bad) {
        status.getStyleClass().setAll("label", bad ? "card-problem" : "card-note");
        status.setText(text);
    }

    private static String firstLine(String text) {
        return text == null ? "FAILED" : text.strip().lines().findFirst().orElse("FAILED").toUpperCase(Locale.ROOT);
    }

    // Root

    private void runWithRoot(Runnable action) {
        if (apt != null && apt.needsPassword() && root != null) {
            showOverlay(new SudoPrompt(root.privilege(),
                    (command, done) -> worker.execute(() -> {
                        CommandEntry entry = apt.verify(command);
                        Platform.runLater(() -> done.accept(entry.success()));
                    }),
                    () -> {
                        root.refresh();
                        hideOverlay();
                        action.run();
                    },
                    this::hideOverlay));
        } else {
            action.run();
        }
    }

    private void showOverlay(Region card) {
        hideOverlay();
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        overlay = new StackPane(scrim, card);
        overlay.setMinSize(0, 0);
        getChildren().add(overlay);
    }

    private void hideOverlay() {
        if (overlay != null) {
            getChildren().remove(overlay);
            overlay = null;
        }
    }

    // Building

    private VBox buildMasthead() {
        Label mark = label("P", "sheet-mark");
        Label kicker = label("PACKAGES.SCOPE", "series");
        HBox identity = new HBox(12, mark, kicker);
        identity.setAlignment(Pos.CENTER_LEFT);

        VBox naming = new VBox(2, headline, caption);
        tagRow.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox readout = new HBox(0, readout("INSTALLED", installedValue), rule(),
                readout("WAITING", waitingValue), rule(), readout("SECURITY", securityValue));
        readout.getStyleClass().add("readout-bar");
        readout.setAlignment(Pos.CENTER_LEFT);
        HBox title = new HBox(26, naming, tagRow, spacer, readout);
        title.setAlignment(Pos.CENTER_LEFT);

        VBox masthead = new VBox(12, identity, title, buildRail());
        masthead.getStyleClass().add("sheet-masthead");
        masthead.setPadding(new Insets(14, 34, 0, 26));
        return masthead;
    }

    private HBox buildRail() {
        installField.getStyleClass().add("card-field");
        installField.setPromptText("package to install");
        installField.setPrefColumnCount(16);
        installField.setOnAction(event -> propose(installField.getText(), true));

        Button install = rail("+ INSTALL", event -> propose(installField.getText(), true));
        Button update = rail("↻ UPDATE INDEX", event -> showIndex());
        // Both open the card that lists what is waiting and then shows apt working, rather than a word to type
        // and a blank screen for however many minutes the download takes.
        Button upgrade = rail("UPGRADE ALL", event -> showUpgrade(false));
        Button full = rail("FULL UPGRADE", event -> showUpgrade(true));
        Button autoremove = rail("AUTOREMOVE", event -> showAutoremove());
        Button clean = rail("CLEAN CACHE", event -> run("CLEANING THE CACHE", apt::clean));
        Button sources = rail("SOURCES & KEYS", event -> showSources());
        Button all = rail("SHOW EVERYTHING", event -> {
            tree.closeAll();
            role = null;
            section = null;
            refreshList();
        });
        Button zoomIn = rail("+", event -> tree.zoomIn());
        Button zoomOut = rail("−", event -> tree.zoomOut());
        Button fit = rail("FIT", event -> tree.fit());
        Button legend = rail("?", event -> showOverlay(new PackagesHelp(this::hideOverlay)));
        legend.setTooltip(new javafx.scene.control.Tooltip("What the colours, the rings and the arcs mean"));

        workingChip.getStyleClass().add("card-warning");
        workingChip.setVisible(false);
        workingChip.setManaged(false);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox rail = new HBox(8, installField, install, separator(), update, upgrade, full,
                separator(), autoremove, clean, sources, workingChip, spacer,
                zoomOut, zoomIn, fit, separator(), all, legend);
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.setPadding(new Insets(0, 0, 10, 0));
        return rail;
    }

    private VBox buildPanel() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(10, listTitle, spacer, listCount);
        head.setAlignment(Pos.CENTER_LEFT);
        listNote.setWrapText(true);

        askedForOnly.getStyleClass().add("rail-button");
        askedForOnly.setFocusTraversable(false);
        askedForOnly.setOnAction(event -> {
            tree.setAskedForOnly(askedForOnly.isSelected());
            refreshList();
        });
        for (ToggleButton filter : new ToggleButton[]{securityOnly, orphansOnly}) {
            filter.getStyleClass().add("rail-button");
            filter.setFocusTraversable(false);
            filter.setMaxWidth(Double.MAX_VALUE);
            filter.setOnAction(event -> {
                // The two ask opposite questions, so holding both down would always answer nothing.
                if (filter.isSelected()) {
                    (filter == securityOnly ? orphansOnly : securityOnly).setSelected(false);
                }
                refreshList();
            });
        }
        // One row of three, not three rows. Each row taken here is a row taken from the list, which is the
        // part anybody came to read.
        javafx.scene.layout.FlowPane quick = new javafx.scene.layout.FlowPane(6, 6,
                securityOnly, orphansOnly, askedForOnly);
        for (ToggleButton filter : new ToggleButton[]{securityOnly, orphansOnly, askedForOnly}) {
            filter.setMaxWidth(Region.USE_PREF_SIZE);
        }

        filterField.getStyleClass().add("card-field");
        filterField.setPromptText("filter these packages");
        filterField.textProperty().addListener((observable, before, value) -> refreshList());

        list.setItems(shown);
        list.getStyleClass().add("redis-list");
        list.setFixedCellSize(34);
        list.setPlaceholder(label("NOTHING HERE.", "metric-label"));
        list.setCellFactory(view -> new PackageCell());
        list.getSelectionModel().selectedItemProperty()
                .addListener((observable, before, chosen) -> tree.reveal(chosen));
        VBox.setVgrow(list, Priority.ALWAYS);

        FlowPane actions = new FlowPane(8, 8,
                action("REMOVE SELECTED", "danger-button", event -> {
                    PackageEntry chosen = list.getSelectionModel().getSelectedItem();
                    if (chosen == null) {
                        report("PICK A PACKAGE FIRST.", true);
                    } else if (chosen.essential()) {
                        report("REFUSING: " + chosen.name().toUpperCase(Locale.ROOT)
                                + " IS ESSENTIAL AND REMOVING IT WOULD BREAK THE SERVER.", true);
                    } else {
                        propose(chosen.name(), false);
                    }
                }),
                action("WHAT IS IT?", "rail-button", event -> {
                    PackageEntry chosen = list.getSelectionModel().getSelectedItem();
                    if (chosen == null || apt == null) {
                        report("PICK A PACKAGE FIRST.", true);
                        return;
                    }
                    showSheet(chosen.name());
                }));
        actions.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        notesBox.setVisible(false);
        notesBox.setManaged(false);

        // The list takes whatever is left, and is never squeezed to two rows by what sits around it.
        list.setMinHeight(240);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox column = new VBox(8, head, listNote, quick, filterField, list, actions, status,
                label("WHAT KUBO COULD NOT READ", "series"), notesBox);
        column.getStyleClass().add("redis-inspector");
        column.setPadding(new Insets(16, 26, 16, 20));
        column.setPrefWidth(392);
        column.setMinWidth(320);
        return column;
    }

    /**
     * One package: what it is called, what it weighs, and whether something is waiting for it.
     */
    private static final class PackageCell extends ListCell<PackageEntry> {

        private final Circle dot = new Circle(3.5);
        private final Label name = label("", "redis-row-name");
        private final Label note = label("", "redis-row-ttl");
        private final Label size = label("", "redis-row-ttl");
        private final VBox lines = new VBox(1, name, note);
        private final HBox row = new HBox(9, dot, lines, size);

        PackageCell() {
            getStyleClass().add("redis-row");
            row.setAlignment(Pos.CENTER_LEFT);
            lines.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(lines, Priority.ALWAYS);
        }

        @Override
        protected void updateItem(PackageEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Color colour = entry.security() ? ALERT : entry.upgradable() ? AMBER
                    : entry.autoRemovable() || !entry.manual() ? PackagePalette.QUIET : LIVE;
            dot.setFill(colour);
            name.setText(entry.name());
            note.setText(entry.upgradable()
                    ? entry.version() + "  →  " + entry.candidate() + (entry.security() ? "   SECURITY" : "")
                    : entry.autoRemovable() ? entry.version() + "   NO LONGER NEEDED"
                    : entry.version() + (entry.essential() ? "   ESSENTIAL" : ""));
            note.setStyle("-fx-text-fill: " + web(colour) + ";");
            size.setText(bytes(entry.installedBytes()));
            setGraphic(row);
        }
    }

    // Small shared pieces

    private VBox readout(String caption, Label value) {
        VBox box = new VBox(0, label(caption, "readout-caption"), value);
        box.setPadding(new Insets(0, 20, 0, 20));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("readout-rule");
        rule.setMinWidth(1);
        rule.setPrefWidth(1);
        rule.setMinHeight(30);
        return rule;
    }

    private static Region separator() {
        Region separator = new Region();
        separator.getStyleClass().add("rail-separator");
        separator.setMinWidth(1);
        separator.setPrefWidth(1);
        separator.setMinHeight(18);
        return separator;
    }

    private static Label tag(String text) {
        Label label = new Label("▸" + text);
        label.getStyleClass().add("sheet-tag");
        return label;
    }

    private static Button rail(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("rail-button");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(action);
        return button;
    }

    private static Button action(String text, String styleClass,
                                 javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        if (styleClass.equals("danger-button")) {
            button.getStyleClass().add("compact-button");
        }
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(handler);
        return button;
    }

    private static String bytes(long value) {
        if (value >= 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.1f GB", value / 1_073_741_824.0);
        }
        if (value >= 1_048_576L) {
            return String.format(Locale.ROOT, "%.0f MB", value / 1_048_576.0);
        }
        return String.format(Locale.ROOT, "%.0f KB", value / 1024.0);
    }

    private static String web(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    /** A wrapped line for inside a card, where the width is the card's and not the panel's. */
    private static Label plainNote(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(760);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    // Previews reach in through these.

    PackageTree tree() {
        return tree;
    }

    void showForPreview(PackageInventory read) {
        apply(read);
    }

    void showLegendForPreview() {
        showOverlay(new PackagesHelp(this::hideOverlay));
    }
}
