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
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The form that writes a new site, with the file it will write beside it.
 *
 * <p>Read the way the machine block is read: one key, its answer, one line each. Nobody should agree to a change
 * to their web server on the strength of a form, so the file is on screen the whole time, updating as the
 * answers change. It is the same text that will be written, not an illustration of it: {@link SiteDraft#toConfig()}
 * produces both.
 *
 * <p>Nothing here is a free-form configuration editor. A shape, a domain, a destination and a few switches, each
 * refused before anything is written if it is not what it claims to be. The ports this server already answers on
 * are offered as buttons, because the port is the one answer somebody has to go and look up.
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
    private final Label presetNote = muted("");
    private final FlowPane targetPills = new FlowPane(6, 6);
    private final HBox securityPills = new HBox(6);
    private final FlowPane ratePills = new FlowPane(6, 6);
    private final VBox targetFields = new VBox(8);
    private final VBox securityFields = new VBox(8);

    private final Button websocket = new Button("websockets");
    private final Button gzip = new Button("compression");
    private final Button singlePage = new Button("unknown paths to index.html");
    private final FlowPane switches = new FlowPane(6, 6);

    private final TextArea preview = new TextArea();
    private final Label fileName = new Label();
    private final Label readsBack = new Label();
    private final Label problems = new Label();
    private final Label summary = new Label();
    private final Button create = new Button("create and switch on");
    private final Pulse working = new Pulse();
    private final HBox rail = new HBox(14);

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

        getStyleClass().addAll("float-card", "wide-card", "redis-help");
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setSpacing(16);
        setPadding(new Insets(24, 30, 22, 28));

        Label title = new Label("new site");
        title.getStyleClass().add("fetch-host");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("close");
        close.getStyleClass().add("ghost-button");
        close.setFocusTraversable(false);
        close.setOnAction(event -> onClose.run());
        HBox header = new HBox(12, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox body = new HBox(26, buildForm(), buildPreview());
        body.setAlignment(Pos.TOP_LEFT);

        problems.getStyleClass().add("card-problem");
        problems.setWrapText(true);
        problems.setMaxWidth(980);
        problems.setMinHeight(Region.USE_PREF_SIZE);
        summary.getStyleClass().add("fetch-value");
        summary.setWrapText(true);
        summary.setMaxWidth(980);
        summary.setMinHeight(Region.USE_PREF_SIZE);

        create.getStyleClass().addAll("primary-button", "compact-button");
        create.setFocusTraversable(false);
        create.setOnAction(event -> ask());
        readsBack.getStyleClass().add("home-muted");
        rail.getChildren().addAll(create, readsBack);
        rail.setAlignment(Pos.CENTER_LEFT);

        Region rule = new Region();
        rule.getStyleClass().add("fetch-rule");
        rule.setMinHeight(1);
        rule.setMaxHeight(1);
        getChildren().addAll(header, body, rule, summary, problems, working, rail);
        redraw();
    }

    // The form

    private ScrollPane buildForm() {
        domains.setPromptText("api.example.com  www.api.example.com");
        watch(domains, 28);
        port.setPromptText("3000");
        watch(port, 7);
        root.setPromptText("/var/www/site");
        watch(root, 22);
        upstream.setPromptText(pools.isEmpty() ? "backend_nodes" : pools.get(0).name());
        watch(upstream, 22);
        socket.setPromptText(SiteDraft.DEFAULT_PHP_SOCKET);
        watch(socket, 22);
        certificate.setPromptText("/etc/letsencrypt/live/example.com/fullchain.pem");
        watch(certificate, 22);
        certificateKey.setPromptText("/etc/letsencrypt/live/example.com/privkey.pem");
        watch(certificateKey, 22);

        for (Preset choice : Preset.values()) {
            presetPills.getChildren().add(pill(choice.title().toLowerCase(Locale.ROOT), () -> preset == choice,
                    () -> apply(choice)));
        }
        for (Target choice : Target.values()) {
            targetPills.getChildren().add(pill(choice.label().toLowerCase(Locale.ROOT), () -> target == choice, () -> {
                target = choice;
                preset = null;
                redraw();
            }));
        }
        for (Security choice : Security.values()) {
            securityPills.getChildren().add(pill(choice.pill().toLowerCase(Locale.ROOT), () -> security == choice,
                    () -> {
                        security = choice;
                        redraw();
                    }));
        }
        for (RateLimit choice : RateLimit.values()) {
            ratePills.getChildren().add(pill(choice.pill().toLowerCase(Locale.ROOT), () -> rate == choice, () -> {
                rate = choice;
                redraw();
            }));
        }
        securityPills.setAlignment(Pos.CENTER_LEFT);

        toggle(websocket, () -> wantsWebsocket = !wantsWebsocket);
        toggle(gzip, () -> wantsGzip = !wantsGzip);
        toggle(singlePage, () -> wantsSinglePage = !wantsSinglePage);

        GridPane form = new GridPane();
        form.setHgap(18);
        form.setVgap(16);
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(78);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(keys, values);
        row(form, 0, "kind", new VBox(6, presetPills, presetNote));
        row(form, 1, "domain", domains);
        row(form, 2, "answered by", new VBox(8, targetPills, targetFields));
        row(form, 3, "https", new VBox(8, securityPills, securityFields));
        row(form, 4, "limit", ratePills);
        row(form, 5, "extras", switches);
        form.setPadding(new Insets(0, 14, 0, 0));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("track-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(470);
        scroll.setPrefViewportHeight(450);
        scroll.setMinWidth(486);
        return scroll;
    }

    private static void row(GridPane form, int at, String key, Node value) {
        Label name = new Label(key);
        name.getStyleClass().add("fetch-key");
        name.setMinWidth(Region.USE_PREF_SIZE);
        GridPane.setValignment(name, javafx.geometry.VPos.TOP);
        name.setPadding(new Insets(4, 0, 0, 0));
        form.add(name, 0, at);
        form.add(value, 1, at);
    }

    private VBox buildPreview() {
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.getStyleClass().add("redis-diagnostic-output");
        preview.setPrefRowCount(24);
        preview.setPrefColumnCount(56);
        VBox.setVgrow(preview, Priority.ALWAYS);

        Label title = new Label("the file");
        title.getStyleClass().add("fetch-key");
        fileName.getStyleClass().add("home-muted");
        HBox head = new HBox(12, title, fileName);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox column = new VBox(8, head, preview);
        column.setPrefWidth(520);
        column.setMinWidth(500);
        return column;
    }

    private void watch(TextField input, int columns) {
        input.getStyleClass().add("card-field");
        input.setPrefColumnCount(columns);
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

    /** One answer under another, with a small key in front: the port, the folder, the certificate. */
    private static HBox sub(String key, Node input) {
        Label name = new Label(key);
        name.getStyleClass().add("port-quiet");
        name.setMinWidth(64);
        HBox line = new HBox(10, name, input);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    /**
     * A path field with a button beside it that walks there instead. Every path on this form has one: a path
     * typed from memory is the likeliest thing on it to be wrong.
     */
    private HBox pathField(String key, TextField input, com.kubo.model.files.PathKind kind) {
        Button choose = new Button("browse");
        choose.getStyleClass().add("ghost-button");
        choose.setFocusTraversable(false);
        choose.setMinWidth(Region.USE_PREF_SIZE);
        choose.setOnAction(event -> hands.pick(kind, startFor(input, kind), chosen -> {
            input.setText(chosen);
            // Let's Encrypt puts the key beside the certificate, under a name that never changes.
            if (kind == com.kubo.model.files.PathKind.CERTIFICATE && certificateKey.getText().isBlank()) {
                certificateKey.setText(com.kubo.model.files.PathKind.keyBeside(chosen));
            }
        }));
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox line = sub(key, input);
        line.getChildren().add(choose);
        return line;
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
        presetNote.setText(preset == null ? "" : preset.why());
        presetNote.setVisible(preset != null);
        presetNote.setManaged(preset != null);

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
        fileName.setText(draft.serverNames().isEmpty() ? "" : "/etc/nginx/sites-available/" + draft.fileName());
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
        readsBack.setText(round ? "read back: the file says what was asked" : "nginx is asked before it goes live");
        readsBack.getStyleClass().setAll("label", round ? "journal-ok" : "home-muted");
    }

    private void drawTarget() {
        targetFields.getChildren().clear();
        switch (target) {
            case PORT -> {
                targetFields.getChildren().add(sub("port", port));
                drawPortHints();
            }
            case FILES -> targetFields.getChildren().add(
                    pathField("folder", root, com.kubo.model.files.PathKind.FOLDER));
            case UPSTREAM -> {
                targetFields.getChildren().add(sub("pool", upstream));
                if (pools.isEmpty()) {
                    targetFields.getChildren().add(muted("this server names no pool, and Kubo does not write one"));
                } else {
                    FlowPane names = new FlowPane(6, 6);
                    for (Upstream pool : pools) {
                        names.getChildren().add(hint(pool.name(), pool.servers().size() + " servers",
                                () -> upstream.setText(pool.name())));
                    }
                    targetFields.getChildren().add(names);
                }
            }
            case PHP -> targetFields.getChildren().addAll(
                    pathField("folder", root, com.kubo.model.files.PathKind.FOLDER),
                    pathField("php-fpm", socket, com.kubo.model.files.PathKind.SOCKET));
        }
    }

    /**
     * The ports this server already answers on, as buttons. Ports the internet can already reach are left out:
     * putting a proxy in front of something already exposed is not what this form is for.
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
        targetFields.getChildren().add(hints);
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
        Button button = new Button(who.isBlank() ? value : value + "  " + who);
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
                    pathField("cert", certificate, com.kubo.model.files.PathKind.CERTIFICATE),
                    pathField("key", certificateKey, com.kubo.model.files.PathKind.PRIVATE_KEY),
                    muted("port 443 with these files, port 80 sending everyone to it"));
        } else {
            securityFields.getChildren().add(muted("port 80, the shape certbot expects to add https to later"));
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
                names.add(word.strip().toLowerCase(Locale.ROOT));
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
        working.setText("is " + draft.fileName() + " already there");
        working.setRunning(true);
        hands.exists(draft.fileName(), exists -> {
            if (Boolean.TRUE.equals(exists)) {
                working.setRunning(false);
                say(draft.fileName() + " is already in sites-available and Kubo will not write over it. "
                        + "Change the domain, or remove that file first.", true);
                create.setDisable(false);
                return;
            }
            write(draft);
        });
    }

    private void write(SiteDraft draft) {
        working.setText("writing, testing, switching on");
        working.setRunning(true);
        hands.create(draft, trouble -> {
            working.setRunning(false);
            if (trouble == null) {
                say(draft.fileName() + " is written, switched on, and nginx reloaded", false);
                onCreated.run();
                return;
            }
            say("nginx refused it, so nothing serves it: " + trouble + ". The file stays in sites-available, "
                    + "switched off, so you can read what it says.", true);
            offerToRemove(draft.fileName());
            create.setDisable(false);
        });
    }

    /**
     * A site Nginx refused is left on disk, off. The file is the only thing that says why, so it is kept, and
     * removing it is offered rather than done.
     */
    private void offerToRemove(String site) {
        Button remove = new Button("remove the file");
        remove.getStyleClass().addAll("ghost-button", "port-danger");
        remove.setFocusTraversable(false);
        remove.setOnAction(event -> {
            working.setText("removing " + site);
            working.setRunning(true);
            hands.delete(site, trouble -> {
                working.setRunning(false);
                say(trouble == null ? site + " is removed" : trouble, trouble != null);
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

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("home-muted");
        label.setWrapText(true);
        label.setMaxWidth(380);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}
