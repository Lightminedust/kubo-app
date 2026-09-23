package com.kubo.ui.network;

import com.kubo.model.network.Endpoint;
import com.kubo.model.network.SiteDraft;
import com.kubo.model.network.SiteDraft.Preset;
import com.kubo.model.network.SiteDraft.RateLimit;
import com.kubo.model.network.SiteDraft.Security;
import com.kubo.model.network.SiteDraft.Target;
import com.kubo.model.network.Upstream;
import com.kubo.ui.Pulse;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The form that writes a new site, with the file it will write shown beside it.
 *
 * <p>Nobody should agree to a change to their web server on the strength of a form, so the file is on screen the
 * whole time, updating as the answers change. It is the same text that will be written, not an illustration of
 * it: {@link SiteDraft#toConfig()} produces both.
 *
 * <p>Nothing here is a free-form configuration editor. A shape, a domain, a destination and a few switches, each
 * one refused before anything is written if the answer is not what it claims to be. The mistakes this exists to
 * prevent are the ones that cannot be made when the file is built instead of typed.
 *
 * <p>The ports this server already answers on are offered as buttons, because the port is the one answer
 * somebody has to go and look up, and the screen behind this card read it a moment ago.
 */
final class NewSiteCard extends VBox {

    interface Hands {
        /** True when a file of this name is already on the server. Answers on the JavaFX thread. */
        void exists(String site, java.util.function.Consumer<Boolean> done);

        /** Writes it, switches it on, and reports with null when Nginx accepted it. */
        void create(SiteDraft draft, java.util.function.Consumer<String> done);

        /** Removes a site that was just created and refused. */
        void delete(String site, java.util.function.Consumer<String> done);

        /**
         * Opens a picker on the server for one kind of path, starting from this one, and hands back what was
         * chosen.
         */
        void pick(com.kubo.model.files.PathKind kind, String start, java.util.function.Consumer<String> chosen);
    }

    private final Hands hands;
    private final List<Upstream> pools;
    private final List<Endpoint> listening;
    private final Runnable onCreated;

    private final TextField domains = new TextField();
    private final TextField port = new TextField();
    private final TextField root = new TextField();
    private final TextField upstream = new TextField();
    private final TextField socket = new TextField();
    private final TextField certificate = new TextField();
    private final TextField certificateKey = new TextField();

    private final FlowPane presetPills = new FlowPane(6, 6);
    private final Label presetNote = note("");
    private final FlowPane targetPills = new FlowPane(6, 6);
    private final HBox securityPills = new HBox(6);
    private final FlowPane ratePills = new FlowPane(6, 6);
    private final Label rateNote = note("");
    private final VBox targetFields = new VBox(10);
    private final VBox securityFields = new VBox(10);

    private final Button websocket = new Button("WEBSOCKETS");
    private final Button gzip = new Button("COMPRESSION");
    private final Button singlePage = new Button("UNKNOWN PATHS TO index.html");
    private final HBox switches = new HBox(6);

    private final TextArea preview = new TextArea();
    private final Label readsBack = new Label();
    private final Label problems = new Label();
    private final Label summary = new Label();
    private final Button create = new Button("CREATE AND SWITCH ON");
    private final Pulse working = new Pulse();
    private final HBox rail = new HBox(12);

    private Preset preset;
    private Target target = Target.PORT;
    private Security security = Security.PLAIN;
    private RateLimit rate = RateLimit.NONE;
    private boolean wantsWebsocket;
    private boolean wantsGzip;
    private boolean wantsSinglePage;

    /**
     * @param listening what this server already answers on, for the port buttons
     */
    NewSiteCard(Hands hands, List<Upstream> pools, List<Endpoint> listening, Runnable onCreated,
                Runnable onClose) {
        this.hands = hands;
        this.pools = pools == null ? List.of() : List.copyOf(pools);
        this.listening = listening == null ? List.of() : List.copyOf(listening);
        this.onCreated = onCreated;

        getStyleClass().addAll("float-card", "wide-card");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(14);
        setPadding(new Insets(24, 30, 24, 28));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, label("A NEW SITE", "card-title"), spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox body = new HBox(24, buildForm(), buildPreview());
        body.setAlignment(Pos.TOP_LEFT);

        problems.getStyleClass().add("card-problem");
        problems.setWrapText(true);
        problems.setMaxWidth(940);
        problems.setMinHeight(Region.USE_PREF_SIZE);
        summary.getStyleClass().add("card-note");
        summary.setWrapText(true);
        summary.setMaxWidth(940);
        summary.setMinHeight(Region.USE_PREF_SIZE);

        create.getStyleClass().addAll("rail-button", "compact-button");
        create.setFocusTraversable(false);
        create.setOnAction(event -> ask());
        rail.getChildren().addAll(create, readsBack);
        rail.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(header, body, summary, problems, working, rail);
        redraw();
    }

    // The form

    private ScrollPane buildForm() {
        domains.setPromptText("api.gemmi-e.com  www.api.gemmi-e.com");
        watch(domains);
        port.setPromptText("8080");
        watch(port);
        root.setPromptText("/var/www/site");
        watch(root);
        upstream.setPromptText(pools.isEmpty() ? "backend_nodes" : pools.get(0).name());
        watch(upstream);
        socket.setPromptText(SiteDraft.DEFAULT_PHP_SOCKET);
        watch(socket);
        certificate.setPromptText("/etc/letsencrypt/live/api.gemmi-e.com/fullchain.pem");
        watch(certificate);
        certificateKey.setPromptText("/etc/letsencrypt/live/api.gemmi-e.com/privkey.pem");
        watch(certificateKey);

        for (Preset choice : Preset.values()) {
            presetPills.getChildren().add(pill(choice.title(), () -> preset == choice, () -> apply(choice)));
        }
        for (Target choice : Target.values()) {
            targetPills.getChildren().add(pill(choice.label(), () -> target == choice, () -> {
                target = choice;
                preset = null;
                redraw();
            }));
        }
        for (Security choice : Security.values()) {
            securityPills.getChildren().add(pill(choice.pill(), () -> security == choice, () -> {
                security = choice;
                redraw();
            }));
        }
        for (RateLimit choice : RateLimit.values()) {
            ratePills.getChildren().add(pill(choice.pill(), () -> rate == choice, () -> {
                rate = choice;
                redraw();
            }));
        }
        securityPills.setAlignment(Pos.CENTER_LEFT);

        toggle(websocket, () -> wantsWebsocket = !wantsWebsocket);
        toggle(gzip, () -> wantsGzip = !wantsGzip);
        toggle(singlePage, () -> wantsSinglePage = !wantsSinglePage);
        switches.setAlignment(Pos.CENTER_LEFT);

        VBox form = new VBox(16,
                new VBox(6, label("WHAT KIND OF SITE", "series"), presetPills, presetNote),
                field("THE DOMAIN", "What a visitor types. Several, separated by spaces.", domains),
                new VBox(6, label("WHAT ANSWERS IT", "series"), targetPills, targetFields),
                new VBox(6, label("HOW IT ANSWERS", "series"), securityPills, securityFields),
                new VBox(6, label("HOW MANY REQUESTS ONE ADDRESS MAY MAKE", "series"), ratePills, rateNote),
                new VBox(6, label("AND", "series"), switches));
        form.setPadding(new Insets(0, 14, 0, 0));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("plain-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(430);
        scroll.setPrefViewportHeight(470);
        scroll.setMinWidth(446);
        return scroll;
    }

    private VBox buildPreview() {
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("redis-diagnostic-output");
        preview.setPrefRowCount(24);
        preview.setPrefColumnCount(56);

        VBox column = new VBox(6,
                label("THE FILE THIS WILL WRITE", "series"),
                note("Read it. It is the file itself, not a picture of it, and nothing else will be changed."),
                preview);
        column.setPrefWidth(520);
        column.setMinWidth(520);
        return column;
    }

    private void watch(TextField input) {
        input.setPrefColumnCount(26);
        input.textProperty().addListener((observable, before, now) -> redraw());
    }

    private void toggle(Button button, Runnable flip) {
        button.getStyleClass().add("service-chip");
        button.setFocusTraversable(false);
        button.setOnAction(event -> {
            flip.run();
            preset = null;
            redraw();
        });
    }

    /** Takes a preset's answers, leaving the domain and anything it says nothing about. */
    private void apply(Preset choice) {
        SiteDraft applied = draft().with(choice);
        preset = choice;
        target = applied.target();
        wantsWebsocket = applied.websocket();
        wantsGzip = applied.gzip();
        wantsSinglePage = applied.singlePage();
        if (!applied.socket().isEmpty()) {
            socket.setText(applied.socket());
        }
        redraw();
    }

    private static VBox field(String title, String why, TextField input) {
        return new VBox(4, label(title, "series"), note(why), input);
    }

    /**
     * A path field with a button beside it that walks there instead. Every path on this form has one: a path
     * typed from memory is the likeliest thing on it to be wrong.
     */
    private VBox pathField(String title, String why, TextField input, com.kubo.model.files.PathKind kind) {
        Button choose = new Button("CHOOSE ON THE SERVER");
        choose.getStyleClass().add("field-button");
        choose.setFocusTraversable(false);
        choose.setOnAction(event -> hands.pick(kind, startFor(input, kind), chosen -> {
            input.setText(chosen);
            // Let's Encrypt puts the key beside the certificate, under a name that never changes.
            if (kind == com.kubo.model.files.PathKind.CERTIFICATE && certificateKey.getText().isBlank()) {
                certificateKey.setText(com.kubo.model.files.PathKind.keyBeside(chosen));
            }
        }));
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox line = new HBox(8, input, choose);
        line.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, label(title, "series"), note(why), line);
    }

    private Button pill(String text, java.util.function.BooleanSupplier on, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("service-chip");
        button.setFocusTraversable(false);
        button.setUserData(on);
        button.setOnAction(event -> action.run());
        return button;
    }

    /**
     * Where a picker opens: the folder of what the field already says, or the certificate's folder for its key,
     * or the kind's usual place.
     */
    private String startFor(TextField input, com.kubo.model.files.PathKind kind) {
        String typed = input.getText().strip();
        if (typed.startsWith("/")) {
            return kind.wantsFolder() ? typed : com.kubo.ui.Crumbs.parent(typed);
        }
        if (kind == com.kubo.model.files.PathKind.PRIVATE_KEY && certificate.getText().strip().startsWith("/")) {
            return com.kubo.ui.Crumbs.parent(certificate.getText().strip());
        }
        return kind.start();
    }

    // Drawing

    private void redraw() {
        paintPills(presetPills.getChildren());
        paintPills(targetPills.getChildren());
        paintPills(securityPills.getChildren());
        paintPills(ratePills.getChildren());
        paint(websocket, wantsWebsocket);
        paint(gzip, wantsGzip);
        paint(singlePage, wantsSinglePage);
        presetNote.setText(preset == null ? "Or answer the questions below." : preset.why());
        rateNote.setText(rate.label());

        switches.getChildren().setAll(gzip);
        if (target == Target.PORT || target == Target.UPSTREAM) {
            switches.getChildren().add(0, websocket);
        }
        if (target == Target.FILES) {
            switches.getChildren().add(singlePage);
        }

        drawTarget();
        drawSecurity();

        SiteDraft draft = draft();
        preview.setText(draft.toConfig());
        List<String> wrong = draft.problems();
        problems.getStyleClass().setAll("label", "card-problem");
        problems.setText(String.join("\n", wrong));
        problems.setVisible(!wrong.isEmpty());
        problems.setManaged(!wrong.isEmpty());
        summary.setText(wrong.isEmpty() ? draft.summary() : "");
        summary.setVisible(wrong.isEmpty());
        summary.setManaged(wrong.isEmpty());
        create.setDisable(!wrong.isEmpty());

        // Not "nginx -t": nothing is written yet, so nginx has nothing to test. What this says is what it
        // checked, and the check is a real one: Kubo read its own file back and found what it put in. Nginx
        // is still asked, before the site is switched on, and it is Nginx that decides.
        boolean round = draft.readsBack();
        readsBack.setText(round
                ? "KUBO READ THIS FILE BACK AND FOUND WHAT IT PUT IN"
                : "NGINX IS ASKED BEFORE ANYTHING IS SWITCHED ON");
        readsBack.getStyleClass().setAll("label", round ? "card-success" : "card-note");
    }

    private void drawTarget() {
        targetFields.getChildren().clear();
        switch (target) {
            case PORT -> {
                targetFields.getChildren().add(
                        field("THE PORT", "The port the program is listening on, on this server.", port));
                drawPortHints();
            }
            case FILES -> targetFields.getChildren().add(
                    pathField("THE DIRECTORY", "Nginx will serve what is in it.", root,
                            com.kubo.model.files.PathKind.FOLDER));
            case UPSTREAM -> {
                targetFields.getChildren().add(
                        field("THE POOL", "A pool already named in the configuration.", upstream));
                if (pools.isEmpty()) {
                    targetFields.getChildren().add(note("This server names no pool. A pool is an upstream "
                            + "block, and Kubo does not write those."));
                } else {
                    FlowPane names = new FlowPane(6, 6);
                    for (Upstream pool : pools) {
                        names.getChildren().add(hint(pool.name(),
                                pool.servers().size() + " servers", () -> upstream.setText(pool.name())));
                    }
                    targetFields.getChildren().addAll(note("This server names:"), names);
                }
            }
            case PHP -> targetFields.getChildren().addAll(
                    pathField("THE DIRECTORY", "Where the PHP files are.", root,
                            com.kubo.model.files.PathKind.FOLDER),
                    pathField("THE PHP-FPM SOCKET", "Its version is in its name and changes with the "
                            + "distribution. Pick it rather than guess it.", socket,
                            com.kubo.model.files.PathKind.SOCKET));
        }
    }

    /**
     * The ports this server already answers on, as buttons.
     *
     * <p>The port is the one answer somebody has to go and look up, and the screen behind this card read it a
     * moment ago. Ports the internet can already reach are left out: putting a proxy in front of something
     * already exposed is not what this form is for.
     */
    private void drawPortHints() {
        Map<Integer, String> byPort = new LinkedHashMap<>();
        for (Endpoint endpoint : listening) {
            int number = endpoint.socket().port();
            if (endpoint.reach() == Endpoint.Reach.OPEN || !speaksHttp(number)) {
                continue;
            }
            byPort.putIfAbsent(number, endpoint.owner().label());
        }
        if (byPort.isEmpty()) {
            return;
        }
        FlowPane hints = new FlowPane(6, 6);
        for (Map.Entry<Integer, String> entry : byPort.entrySet()) {
            int number = entry.getKey();
            hints.getChildren().add(hint(String.valueOf(number), entry.getValue(),
                    () -> port.setText(String.valueOf(number))));
        }
        targetFields.getChildren().addAll(note("Already listening on this server:"), hints);
    }

    /** Services that do not answer HTTP, so a web proxy in front of them is never what somebody meant. */
    private static final java.util.Set<String> NOT_WEB = java.util.Set.of("FTP", "SSH", "SMTP", "DNS", "POP3",
            "NTP", "IMAP", "MQTT", "Docker", "MySQL", "PostgreSQL", "RabbitMQ", "Redis", "Memcached", "MongoDB",
            "Elasticsearch");

    /**
     * Whether a port is worth offering as a proxy destination. An unnamed port is offered, because that is
     * where every application somebody wrote themselves ends up. 80 and 443 are Nginx itself.
     */
    private static boolean speaksHttp(int number) {
        if (number == 80 || number == 443) {
            return false;
        }
        return !NOT_WEB.contains(com.kubo.model.network.PortNames.of(number));
    }

    /** Something this server already has, as one click. */
    private Button hint(String value, String who, Runnable take) {
        Button button = new Button(who.isBlank() ? value : value + "  ·  " + who);
        button.getStyleClass().addAll("net-chip", "net-chip-docker");
        button.setFocusTraversable(false);
        button.setOnAction(event -> {
            take.run();
            preset = null;
            redraw();
        });
        return button;
    }

    private void drawSecurity() {
        securityFields.getChildren().clear();
        if (security == Security.HTTPS) {
            securityFields.getChildren().addAll(
                    note("Port 443 with these files, and port 80 sending every visitor to it."),
                    pathField("THE CERTIFICATE", "A file this server already has.", certificate,
                            com.kubo.model.files.PathKind.CERTIFICATE),
                    pathField("THE PRIVATE KEY", "Its key, also already there.", certificateKey,
                            com.kubo.model.files.PathKind.PRIVATE_KEY));
        } else {
            securityFields.getChildren().add(note("Port 80. This is the shape certbot expects to find when it "
                    + "is run afterwards: it adds the secure block and the redirect itself. Kubo does not run "
                    + "certbot."));
        }
    }

    private void paintPills(List<javafx.scene.Node> row) {
        for (javafx.scene.Node node : row) {
            Button button = (Button) node;
            paint(button, ((java.util.function.BooleanSupplier) button.getUserData()).getAsBoolean());
        }
    }

    private static void paint(Button button, boolean on) {
        button.getStyleClass().remove("service-chip-active");
        if (on) {
            button.getStyleClass().add("service-chip-active");
        }
    }

    /** The draft as the form has it right now. */
    private SiteDraft draft() {
        List<String> names = new ArrayList<>();
        for (String word : domains.getText().strip().split("[\\s,]+")) {
            if (!word.isBlank()) {
                names.add(word.strip().toLowerCase(java.util.Locale.ROOT));
            }
        }
        int number;
        try {
            number = Integer.parseInt(port.getText().strip());
        } catch (NumberFormatException notANumber) {
            number = 0;
        }
        return new SiteDraft(names, target, number, root.getText(), upstream.getText(), socket.getText(),
                security, certificate.getText(), certificateKey.getText(), wantsWebsocket, wantsGzip,
                wantsSinglePage, rate);
    }

    // Doing it

    /** Asks the server whether the file is already there before writing over anybody's work. */
    private void ask() {
        SiteDraft draft = draft();
        if (!draft.ready()) {
            return;
        }
        create.setDisable(true);
        working.setRunning(true);
        hands.exists(draft.fileName(), exists -> {
            if (Boolean.TRUE.equals(exists)) {
                working.setRunning(false);
                say("A file called " + draft.fileName() + " is already in sites-available. Kubo will not "
                        + "write over it. Change the domain, or remove that file yourself first.", true);
                create.setDisable(false);
                return;
            }
            write(draft);
        });
    }

    private void write(SiteDraft draft) {
        working.setRunning(true);
        hands.create(draft, trouble -> {
            working.setRunning(false);
            if (trouble == null) {
                say(draft.fileName() + " is written, switched on and Nginx reloaded.", false);
                onCreated.run();
                return;
            }
            say("Nginx refused it, so nothing is serving it: " + trouble
                    + "  The file is still in sites-available, switched off, so you can read what it says.",
                    true);
            offerToRemove(draft.fileName());
            create.setDisable(false);
        });
    }

    /**
     * A site Nginx refused is left on disk, off. The file is the only thing that says why, so it is kept, and
     * removing it is offered rather than done.
     */
    private void offerToRemove(String site) {
        Button remove = new Button("REMOVE THE FILE");
        remove.getStyleClass().addAll("danger-button", "compact-button");
        remove.setFocusTraversable(false);
        remove.setOnAction(event -> {
            working.setRunning(true);
            hands.delete(site, trouble -> {
                working.setRunning(false);
                say(trouble == null ? site + " is removed." : trouble, trouble != null);
                rail.getChildren().remove(remove);
            });
        });
        if (!rail.getChildren().contains(remove)) {
            rail.getChildren().add(remove);
        }
    }

    private void say(String text, boolean wrong) {
        problems.getStyleClass().setAll("label", wrong ? "card-problem" : "card-success");
        problems.setText(text);
        problems.setVisible(true);
        problems.setManaged(true);
    }

    /** Only for the previews: fills the form without anybody typing. */
    void showForPreview(String domainText, Preset which, String value) {
        domains.setText(domainText);
        apply(which);
        if (target == Target.PORT) {
            port.setText(value);
        } else {
            root.setText(value);
        }
        redraw();
    }

    private static Label note(String text) {
        Label label = label(text, "card-note");
        label.setWrapText(true);
        label.setMaxWidth(410);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
