package com.kubo.ui.network;

import com.kubo.model.network.BlockedAddress;
import com.kubo.model.network.WhoisSummary;
import com.kubo.ui.ListSelect;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Every address this server has shut out, as a table rather than a count.
 *
 * <p>The map said how many were banned. That number is the least useful thing about them. Two hundred
 * addresses each banned once is a machine doing its job; five addresses banned eleven times each is somebody
 * who has decided to get in, and the two look identical from a number.
 *
 * <p>So: one row each, sorted by who keeps coming back, with when, how often, and who owns the address when
 * that can be asked. The whole thing downloads as JSON, because a window that is closed has answered nothing
 * about last week.
 */
final class BlockedScreen extends VBox {

    /** How the screen reaches the server. */
    interface Hands {

        /** Looks up who owns an address, if the server has whois. */
        void whois(String address, Consumer<WhoisSummary> done);

        /** Lets one address go, everywhere it is held. */
        void release(BlockedAddress blocked, Consumer<String> done);

        /** Writes the journal to a file the operator chooses. */
        void export(List<BlockedAddress> blocked, BiConsumer<Boolean, String> done);

        void back();

        void reread();
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Hands hands;
    private final Instant now;
    private final ObservableList<BlockedAddress> all = FXCollections.observableArrayList();
    private final FilteredList<BlockedAddress> shown = new FilteredList<>(all);
    private final ListView<BlockedAddress> table = new ListView<>(shown);
    private final Map<String, WhoisSummary> owners = new HashMap<>();
    /**
     * Addresses already asked about, answered or not.
     *
     * <p>Without it this screen asked forever. A cell with no owner asked, the answer refreshed the table,
     * the refresh rebuilt every cell, and every cell still without an owner asked again. Five addresses on one
     * connection became an endless queue of whois, which is also why nothing else the screen wanted could get
     * a turn.
     */
    private final java.util.Set<String> asked = new java.util.HashSet<>();
    private final Label tally = new Label();
    /** What is being asked while the table has nothing in it yet. */
    private final com.kubo.ui.WorkTrail trail = new com.kubo.ui.WorkTrail();
    private final Label status = new Label();
    private final TextField search = new TextField();
    private final ToggleGroup sortGroup = new ToggleGroup();
    private final ToggleButton byPersistence = toggle("WHO KEEPS COMING BACK");
    private final ToggleButton byNewest = toggle("MOST RECENT");
    private final ToggleButton byAddress = toggle("BY ADDRESS");
    private final ToggleButton onlyActive = toggle("BLOCKED RIGHT NOW");
    private String problem = "";

    BlockedScreen(Hands hands, Instant now) {
        this.hands = hands;
        this.now = now;
        getStyleClass().add("files-view");
        setSpacing(12);
        setPadding(new Insets(16, 34, 16, 26));

        Label mark = label("BLOCKED", "sheet-title");
        tally.getStyleClass().add("lede");

        Button back = rail("← BACK TO THE MAP", event -> hands.back());
        Button reread = rail("↻ RE-READ", event -> hands.reread());
        Button export = rail("EXPORT JSON", event -> hands.export(List.copyOf(all), (worked, said) ->
                report(said, !worked)));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, back, reread, export, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        byPersistence.setSelected(true);
        for (ToggleButton sort : new ToggleButton[]{byPersistence, byNewest, byAddress}) {
            sort.setToggleGroup(sortGroup);
        }
        sortGroup.selectedToggleProperty().addListener((observable, before, chosen) -> {
            if (chosen == null) {
                before.setSelected(true);
                return;
            }
            resort();
        });
        onlyActive.selectedProperty().addListener((observable, before, on) -> applyFilter());
        search.getStyleClass().add("find-field");
        search.setPromptText("an address, a range or a jail");
        search.setPrefColumnCount(22);
        search.textProperty().addListener((observable, before, text) -> applyFilter());

        HBox sorting = new HBox(14, label("SORT", "metric-label"), byPersistence, byNewest, byAddress,
                onlyActive);
        sorting.setAlignment(Pos.CENTER_LEFT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        sorting.getChildren().addAll(gap, search);

        table.getStyleClass().add("logs-list");
        // Two lines per row, said once, so the list does not have to measure every cell to lay itself out.
        // Left to guess it sized them from the first one and drew the rest on top of the header.
        table.setFixedCellSize(46);
        table.setCellFactory(view -> new Row(view));
        ListSelect.install(table, count -> report(count == 1 ? "COPIED 1 ADDRESS" : "COPIED " + count
                        + " ADDRESSES", false),
                new ListSelect.Copy<>("COPY THE ADDRESSES", rows ->
                        String.join(System.lineSeparator(),
                                rows.stream().map(BlockedAddress::address).toList())),
                new ListSelect.Copy<>("COPY WITH THE COUNTS", BlockedScreen::asText));
        VBox.setVgrow(table, Priority.ALWAYS);

        status.getStyleClass().add("card-note");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.setVisible(false);
        status.setManaged(false);

        trail.setVisible(false);
        trail.setManaged(false);
        getChildren().addAll(mark, tally, actions, trail, sorting, header(), table, status);
    }

    /** The column names, above the rows rather than repeated in each one. */
    private HBox header() {
        HBox row = new HBox(12,
                fixed("ADDRESS", 190), fixed("HELD BY", 110), fixed("TIMES", 70),
                fixed("WHEN", 150), fixed("LETS GO", 120), fixed("WHERE IT IS FROM", 200));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 2, 12));
        return row;
    }

    private static Label fixed(String text, double width) {
        Label label = label(text, "metric-label");
        label.setMinWidth(width);
        label.setPrefWidth(width);
        return label;
    }

    /**
     * Says the screen is reading, and shows what it is asking while it does.
     *
     * <p>Reading this takes several commands over one connection: the jails, then the database, then the
     * firewall rules. A blank table for four seconds looks exactly like a server with nothing to show.
     */
    void reading(boolean yes) {
        trail.setVisible(yes);
        trail.setManaged(yes);
        if (yes) {
            tally.setText("Asking the server what it has shut out…");
            trail.start();
        } else {
            trail.finish();
        }
    }

    /** One command going out, for the trail. */
    void asking(String command) {
        if (trail.running()) {
            trail.add(command);
        }
    }

    /** Fills the screen. */
    void show(List<BlockedAddress> blocked, String problem) {
        this.problem = problem == null ? "" : problem;
        all.setAll(BlockedAddress.merge(blocked));
        resort();
        long active = all.stream().filter(BlockedAddress::active).count();
        long returning = all.stream().filter(BlockedAddress::repeatOffender).count();
        tally.setText(all.isEmpty() ? "Nothing is blocked."
                : all.size() + (all.size() == 1 ? " address" : " addresses") + "  ·  " + active
                        + " blocked right now  ·  " + returning + " have been blocked before");
        if (!this.problem.isEmpty()) {
            report(this.problem, false);
        }
    }

    /** Says who owns an address, once it has been asked. */
    void owner(String address, WhoisSummary summary) {
        if (summary == null) {
            return;
        }
        owners.put(address, summary);
        asked.add(address);
        table.refresh();
    }

    private void resort() {
        List<BlockedAddress> ordered = new java.util.ArrayList<>(all);
        ordered.sort(byNewest.isSelected() ? BlockedAddress.byNewest()
                : byAddress.isSelected() ? BlockedAddress.byAddress()
                : BlockedAddress.byPersistence());
        all.setAll(ordered);
        applyFilter();
    }

    private void applyFilter() {
        String text = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        boolean active = onlyActive.isSelected();
        shown.setPredicate(one -> (!active || one.active())
                && (text.isEmpty() || one.address().toLowerCase(Locale.ROOT).contains(text)
                || one.jail().toLowerCase(Locale.ROOT).contains(text)
                || one.neighbourhood().toLowerCase(Locale.ROOT).contains(text)));
    }

    private static String asText(List<BlockedAddress> rows) {
        StringBuilder text = new StringBuilder();
        for (BlockedAddress one : rows) {
            if (!text.isEmpty()) {
                text.append(System.lineSeparator());
            }
            text.append(one.address()).append("  ").append(one.source().label())
                    .append("  x").append(one.times())
                    .append(one.bannedAt() == null ? "" : "  " + WHEN.format(one.bannedAt()))
                    .append(one.jail().isEmpty() ? "" : "  " + one.jail());
        }
        return text.toString();
    }

    private void report(String text, boolean bad) {
        status.getStyleClass().setAll("label", bad ? "card-problem" : "card-note");
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    /** One address. */
    private final class Row extends ListCell<BlockedAddress> {

        private final ListView<BlockedAddress> view;

        Row(ListView<BlockedAddress> view) {
            this.view = view;
            ListSelect.dragAcross(this);
        }

        @Override
        protected void updateItem(BlockedAddress blocked, boolean empty) {
            super.updateItem(blocked, empty);
            setText(null);
            if (empty || blocked == null) {
                setGraphic(null);
                return;
            }
            Label address = label(blocked.address(), "log-command");
            address.setMinWidth(190);
            address.setPrefWidth(190);

            Label held = label(blocked.source().label(), blocked.active() ? "card-problem" : "mono-muted");
            held.setMinWidth(110);
            held.setPrefWidth(110);

            // The number that says whether this is somebody trying or somebody deciding.
            Label times = label(blocked.times() == 1 ? "once" : blocked.times() + " times",
                    blocked.repeatOffender() ? "card-warning" : "mono-muted");
            times.setMinWidth(70);
            times.setPrefWidth(70);

            Label when = label(blocked.bannedAt() == null ? "not recorded" : WHEN.format(blocked.bannedAt()),
                    "mono-muted");
            when.setMinWidth(150);
            when.setPrefWidth(150);

            Duration left = blocked.remaining(now);
            Label until = label(!blocked.active() ? "let go"
                    : left == null ? "until removed"
                    : left.toHours() > 0 ? "in " + left.toHours() + " h" : "in " + left.toMinutes() + " min",
                    "mono-muted");
            until.setMinWidth(120);
            until.setPrefWidth(120);

            WhoisSummary owner = owners.get(blocked.address());
            Label where = label(owner == null ? "" : owner.line(), "mono-muted");
            where.setMinWidth(200);
            where.setPrefWidth(200);
            if (owner == null && asked.add(blocked.address())) {
                // Once per address, ever, and only for the rows somebody is actually looking at.
                hands.whois(blocked.address(), summary -> owner(blocked.address(), summary));
            }

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button release = new Button("LET IT GO");
            release.getStyleClass().addAll("rail-button", "compact-button");
            release.setFocusTraversable(false);
            release.setDisable(!blocked.active());
            release.setOnAction(event -> {
                release.setDisable(true);
                release.setText("LETTING GO…");
                hands.release(blocked, trouble -> {
                    release.setText(trouble == null ? "LET GO" : "REFUSED");
                    if (trouble != null) {
                        release.setDisable(false);
                        report(trouble, true);
                    }
                });
            });

            HBox row = new HBox(12, address, held, times, when, until, where, spacer, release);
            row.setAlignment(Pos.CENTER_LEFT);
            if (blocked.jail().isEmpty()) {
                setGraphic(row);
                return;
            }
            Label jail = label(blocked.jail(), "metric-label");
            VBox box = new VBox(1, row, jail);
            VBox.setMargin(jail, new Insets(0, 0, 0, 12));
            setGraphic(box);
        }
    }

    /** Only for the previews: the rows and an owner already looked up. */
    void showForPreview(List<BlockedAddress> blocked, Map<String, WhoisSummary> known, String problem) {
        owners.putAll(known);
        show(blocked, problem);
    }

    private static ToggleButton toggle(String text) {
        ToggleButton button = new ToggleButton(text);
        button.getStyleClass().add("mode-toggle");
        button.setFocusTraversable(false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static Button rail(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("rail-button");
        button.setFocusTraversable(false);
        button.setOnAction(action);
        return button;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
