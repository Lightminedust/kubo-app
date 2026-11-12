package com.kubo.ui.network;

import com.kubo.ui.LiveTimer;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a request reaches one site, drawn top to bottom in layers: the domains a visitor types, Nginx, every path
 * the site answers, where each path is sent, the program holding that port, and this machine under all of it.
 *
 * <p>Layers rather than a line, because that is how the thing is built: several names come into one Nginx, Nginx
 * hands each path to something, and several paths often end in the same program or the same folder. Drawn this
 * way, the place where things meet shows, and so does the one link that goes nowhere.
 *
 * <p>It moves a little and only where it means something: the layers come in from the top, light runs down the
 * links that carry requests, a link that ends in nothing is dashed and does not carry any, and pointing at a
 * thing lights the whole way through it.
 */
final class SiteRoute extends Pane {

    /** How one link of the way holds. */
    enum State {
        WORKS(Color.web("#35e0a1")),
        WARNS(Color.web("#ffb347")),
        BROKEN(Color.web("#ff2d55")),
        FILES(Color.web("#35e8ff")),
        UNKNOWN(Color.web("#8d8d9e"));

        final Color colour;

        State(Color colour) {
            this.colour = colour;
        }
    }

    /** The bands, top to bottom. */
    enum Layer {
        DOMAINS("domains"),
        NGINX("nginx"),
        PATHS("paths"),
        TARGETS("sent to"),
        PROGRAMS("programs"),
        MACHINE("machine");

        final String title;

        Layer(String title) {
            this.title = title;
        }
    }

    /** What a node is, which decides the mark it is drawn with. */
    enum Kind { DOMAIN, NGINX, PATH, PORT, POOL, FOLDER, REDIRECT, ANSWER, REMOTE, PROGRAM, MACHINE }

    /**
     * One thing on the way.
     *
     * @param key     what links name it by: two nodes with the same key are the same thing
     * @param badge   a few words in their own colour after the detail, a certificate or "ws"; empty for none
     * @param members for a pool, how each of its servers holds, in the order the file lists them
     * @param open    what a click does, or null when there is nothing to open
     */
    record Node(String key, Layer layer, Kind kind, String name, String detail, String badge, State badgeState,
                State state, List<State> members, Runnable open) {

        Node {
            name = name == null ? "" : name;
            detail = detail == null ? "" : detail;
            badge = badge == null ? "" : badge;
            badgeState = badgeState == null ? State.UNKNOWN : badgeState;
            state = state == null ? State.UNKNOWN : state;
            members = members == null ? List.of() : List.copyOf(members);
        }

        Node(String key, Layer layer, Kind kind, String name, String detail, State state) {
            this(key, layer, kind, name, detail, "", State.UNKNOWN, state, List.of(), null);
        }

        Node badge(String text, State tone) {
            return new Node(key, layer, kind, name, detail, text, tone, state, members, open);
        }

        Node opens(Runnable action) {
            return new Node(key, layer, kind, name, detail, badge, badgeState, state, members, action);
        }

        Node members(List<State> list) {
            return new Node(key, layer, kind, name, detail, badge, badgeState, state, list, open);
        }
    }

    /**
     * One hop, downwards.
     *
     * @param member for a hop leaving a pool, which of its servers it leaves from; -1 otherwise
     */
    record Link(String from, String to, int member) {
    }

    /**
     * Everything one site is.
     *
     * @param on     false for a site that is switched off: drawn faded, with nothing running down it
     * @param broken true when its link in sites-enabled points at a file that is gone
     * @param note   what to say in the paths band when there is no path to draw
     */
    record Graph(List<Node> nodes, List<Link> links, boolean on, boolean broken, String note) {

        Graph {
            nodes = List.copyOf(nodes);
            links = List.copyOf(links);
            note = note == null ? "" : note;
        }
    }

    /** Builds a graph. The first node given for a key is the one kept, and each hop is kept once. */
    static final class Builder {

        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Set<Link> links = new LinkedHashSet<>();

        String add(Node node) {
            nodes.putIfAbsent(node.key(), node);
            return node.key();
        }

        void link(String from, String to) {
            link(from, to, -1);
        }

        void link(String from, String to, int member) {
            if (from != null && to != null && !from.equals(to)) {
                links.add(new Link(from, to, member));
            }
        }

        Graph build(boolean on, boolean broken, String note) {
            return new Graph(new ArrayList<>(nodes.values()), new ArrayList<>(links), on, broken, note);
        }
    }

    /** Where one node landed. Worked out once for a size, then drawn every frame. */
    private static final class Placed {
        final Node node;
        final int band;
        double x;
        double y;
        double halfWidth;
        double halfHeight;
        String name = "";
        String nameBadge = "";
        double nameBadgeX;
        String detail = "";
        String detailBadge = "";
        double detailBadgeX;
        double labelWidth;
        boolean clipped;

        Placed(Node node, int band) {
            this.node = node;
            this.band = band;
        }

        boolean hit(double px, double py) {
            double reach = Math.max(halfHeight, 14) + 2;
            double right = x + halfWidth + (labelWidth > 0 ? 10 + labelWidth : 0) + 4;
            return px >= x - halfWidth - 6 && px <= right && Math.abs(py - y) <= reach;
        }
    }

    private record Band(Layer layer, double top, double height, int count) {
    }

    private static final Color TEXT = Color.web("#eaeaf2");
    private static final Color DIM = Color.web("#a9a9bb");
    private static final Color MUTED = Color.web("#6b6b80");
    private static final Color FAINT = Color.web("#33333f");
    private static final Color DARK = Color.web("#08080d");
    private static final Color TIP = Color.web("#0e0e16");
    private static final Color KEY = Color.web("#ff2d55");
    private static final Color MAGENTA = Color.web("#ff3fd8");
    private static final Color VIOLET = Color.web("#9a6bff");
    private static final Color SILVER = Color.web("#c9c9d6");
    private static final Color BAND_FILL = Color.rgb(255, 255, 255, 0.017);
    private static final Color RULE = Color.rgb(255, 255, 255, 0.045);

    private static final Font TITLE = Font.font("Consolas", FontWeight.BOLD, 13);
    private static final Font NAME = Font.font("Consolas", 12);
    private static final Font DETAIL = Font.font("Consolas", 10.5);
    private static final Font BAND = Font.font("Consolas", 11.5);

    private static final double PAD = 8;
    private static final double MIN_BAND = 70;
    private static final double MAX_BAND = 140;
    private static final double ROW_GAP = 46;
    private static final double MIN_SLOT = 110;
    /** The room kept between the end of one label and the next mark. */
    private static final double SLOT_GAP = 24;
    /** Seconds between one band coming in and the next. */
    private static final double STAGGER = 0.09;
    private static final double NODE_IN = 0.34;
    private static final double LINK_IN = 0.42;
    /** Seconds light takes to run down one link. */
    private static final double TRAVEL = 2.6;
    private static final double MEMBER_GAP = 13;

    private final Canvas canvas = new Canvas();
    private final LiveTimer timer;
    private Graph graph;
    private String signature = "";
    private double shownAt;
    private double frozen = -1;
    private final Map<String, Placed> placed = new LinkedHashMap<>();
    private final List<Band> bands = new ArrayList<>();
    private final Map<String, List<Link>> incoming = new HashMap<>();
    private final Map<String, List<Link>> outgoing = new HashMap<>();
    private double placedWidth = -1;
    private double placedHeight = -1;

    private String hovered;
    /** The node whose way is lit, kept while the light fades after the pointer has left it. */
    private String lit;
    private Set<String> chain = Set.of();
    private double focus;
    private double lastPaint = -1;

    SiteRoute() {
        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        timer = new LiveTimer(this, LiveTimer.AMBIENT, seconds -> paint());
        setOnMouseMoved(this::hover);
        setOnMouseExited(event -> {
            hovered = null;
            setCursor(Cursor.DEFAULT);
        });
        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
                return;
            }
            Placed at = nodeAt(event.getX(), event.getY());
            if (at != null && at.node.open() != null) {
                at.node.open().run();
            }
        });
    }

    /** Draws one site. The layers come in from the top unless it is the same site read again. */
    void show(Graph graph) {
        this.graph = graph;
        incoming.clear();
        outgoing.clear();
        for (Link link : graph.links()) {
            outgoing.computeIfAbsent(link.from(), key -> new ArrayList<>()).add(link);
            incoming.computeIfAbsent(link.to(), key -> new ArrayList<>()).add(link);
        }
        String next = signatureOf(graph);
        if (!next.equals(signature)) {
            // Something else is shown: it comes in. The same site read again only changes in place, so a
            // reading every twenty seconds does not make the screen jump.
            shownAt = now();
            hovered = null;
            lit = null;
            chain = Set.of();
            focus = 0;
        }
        signature = next;
        placedWidth = -1;
        requestLayout();
        paint();
        timer.start();
    }

    void clear() {
        graph = null;
        signature = "";
        placed.clear();
        bands.clear();
        hovered = null;
        timer.stop();
        paint();
    }

    /** Only for the previews: draws the way as it looks this long after it was shown, and holds it there. */
    void freeze(double seconds) {
        frozen = seconds;
        lastPaint = -1;
        paint();
    }

    /** Only for the previews: the way through one node lit, as if the pointer rested on it. */
    void pointForPreview(String key) {
        hovered = key;
        lit = key;
        chain = chainOf(key);
        focus = 1;
        paint();
    }

    private static String signatureOf(Graph graph) {
        StringBuilder text = new StringBuilder(graph.on() + "|" + graph.broken());
        for (Node node : graph.nodes()) {
            text.append('|').append(node.key());
        }
        return text.toString();
    }

    // Size

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinHeight(double width) {
        return needed(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        return needed(width);
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        paint();
    }

    /** The height every band needs at this width, so a long site scrolls rather than squeezes. */
    private double needed(double width) {
        if (graph == null) {
            return 0;
        }
        double w = width > 0 ? width : getWidth() > 0 ? getWidth() : 640;
        double total = PAD * 2;
        for (Layer layer : layersShown()) {
            total += bandNeed(countIn(layer), w);
        }
        return total;
    }

    private List<Layer> layersShown() {
        List<Layer> shown = new ArrayList<>();
        for (Layer layer : Layer.values()) {
            if (countIn(layer) > 0 || (layer == Layer.PATHS && !graph.note().isEmpty())) {
                shown.add(layer);
            }
        }
        return shown;
    }

    private int countIn(Layer layer) {
        int count = 0;
        for (Node node : graph.nodes()) {
            if (node.layer() == layer) {
                count++;
            }
        }
        return count;
    }

    private static double graphLeft(double w) {
        return w >= 560 ? 100 : 82;
    }

    private static int perRow(double w) {
        return Math.max(1, (int) Math.floor((w - 18 - graphLeft(w)) / MIN_SLOT));
    }

    private static double bandNeed(int count, double w) {
        int rows = Math.max(1, (int) Math.ceil(count / (double) perRow(w)));
        return MIN_BAND + (rows - 1) * ROW_GAP;
    }

    // Placing

    private void place(double w, double h) {
        placed.clear();
        bands.clear();
        placedWidth = w;
        placedHeight = h;
        List<Layer> shown = layersShown();
        double left = graphLeft(w);
        double span = w - 18 - left;
        int perRow = perRow(w);

        double[] heights = new double[shown.size()];
        double total = 0;
        for (int b = 0; b < shown.size(); b++) {
            heights[b] = bandNeed(countIn(shown.get(b)), w);
            total += heights[b];
        }
        double room = h - PAD * 2;
        double extra = shown.isEmpty() ? 0 : Math.max(0, room - total) / shown.size();
        double used = 0;
        for (int b = 0; b < heights.length; b++) {
            heights[b] = Math.min(heights[b] + extra, Math.max(heights[b], MAX_BAND));
            used += heights[b];
        }

        double y = PAD + Math.max(0, room - used) / 2;
        Map<String, Double> across = new HashMap<>();
        for (int b = 0; b < shown.size(); b++) {
            Layer layer = shown.get(b);
            List<Node> members = ordered(layer, across);
            bands.add(new Band(layer, y, heights[b], members.size()));
            int rows = Math.max(1, (int) Math.ceil(members.size() / (double) perRow));
            double firstRow = y + heights[b] / 2 - (rows - 1) * ROW_GAP / 2;
            for (int row = 0; row < rows; row++) {
                int first = row * perRow;
                int last = Math.min(members.size(), first + perRow);
                List<Placed> spots = new ArrayList<>();
                double[] natural = new double[last - first];
                for (int i = first; i < last; i++) {
                    Placed spot = new Placed(members.get(i), b);
                    shape(spot);
                    natural[i - first] = naturalWidth(spot);
                    spots.add(spot);
                }
                double[] slots = RowShare.share(natural, span);
                double x = left;
                for (int j = 0; j < spots.size(); j++) {
                    Placed spot = spots.get(j);
                    measure(spot, slots[j]);
                    double block = spot.halfWidth * 2 + (spot.labelWidth > 0 ? 10 + spot.labelWidth : 0);
                    spot.x = x + slots[j] / 2 - block / 2 + spot.halfWidth;
                    spot.y = firstRow + row * ROW_GAP;
                    x += slots[j];
                    placed.put(spot.node.key(), spot);
                    across.put(spot.node.key(), spot.x);
                    if (spot.node.kind() == Kind.POOL) {
                        for (int m = 0; m < spot.node.members().size(); m++) {
                            across.put(spot.node.key() + "#" + m, memberX(spot, m));
                        }
                    }
                }
            }
            y += heights[b];
        }
    }

    /**
     * A band's nodes, each under the leftmost thing that leads to it, so the links cross as little as they can.
     * What nothing leads to keeps the order it was given in.
     */
    private List<Node> ordered(Layer layer, Map<String, Double> across) {
        List<Node> members = new ArrayList<>();
        for (Node node : graph.nodes()) {
            if (node.layer() == layer) {
                members.add(node);
            }
        }
        Map<String, Double> weight = new HashMap<>();
        for (int i = 0; i < members.size(); i++) {
            Node node = members.get(i);
            double best = Double.MAX_VALUE;
            for (Link link : incoming.getOrDefault(node.key(), List.of())) {
                Double from = across.get(link.member() >= 0 ? link.from() + "#" + link.member() : link.from());
                if (from != null) {
                    best = Math.min(best, from);
                }
            }
            weight.put(node.key(), best == Double.MAX_VALUE ? 1e7 + i : best + i * 1e-4);
        }
        members.sort(Comparator.comparingDouble(node -> weight.get(node.key())));
        return members;
    }

    private static void shape(Placed spot) {
        switch (spot.node.kind()) {
            case NGINX -> {
                spot.halfWidth = 13;
                spot.halfHeight = 13;
            }
            case POOL -> {
                int count = Math.max(1, spot.node.members().size());
                spot.halfWidth = Math.max(36, 24 + (count - 1) * MEMBER_GAP) / 2;
                spot.halfHeight = 10;
            }
            case FOLDER -> {
                spot.halfWidth = 9;
                spot.halfHeight = 7;
            }
            case PROGRAM -> {
                spot.halfWidth = 8;
                spot.halfHeight = 8;
            }
            case MACHINE -> {
                spot.halfWidth = 15;
                spot.halfHeight = 11;
            }
            default -> {
                spot.halfWidth = 7;
                spot.halfHeight = 7;
            }
        }
    }

    /** How wide a node and its words are when nothing is cut, with the gap kept before the next one. */
    private static double naturalWidth(Placed spot) {
        Node node = spot.node;
        double first = width(node.name(), node.kind() == Kind.NGINX ? TITLE : NAME);
        if (node.detail().isEmpty() && !node.badge().isEmpty()) {
            first += 8 + width(node.badge(), DETAIL);
        }
        double second = node.detail().isEmpty() ? 0 : width(node.detail(), DETAIL)
                + (node.badge().isEmpty() ? 0 : width("  " + node.badge(), DETAIL));
        return spot.halfWidth * 2 + 10 + Math.max(first, second) + SLOT_GAP;
    }

    /** The words beside a node, cut to the room its slot leaves them. */
    private static void measure(Placed spot, double slot) {
        Node node = spot.node;
        double room = slot - spot.halfWidth * 2 - 10 - SLOT_GAP + 8;
        if (room < 34) {
            spot.labelWidth = 0;
            spot.clipped = true;
            return;
        }
        Font nameFont = node.kind() == Kind.NGINX ? TITLE : NAME;
        boolean badgeOnName = node.detail().isEmpty() && !node.badge().isEmpty();
        double nameRoom = badgeOnName ? room - width(node.badge(), DETAIL) - 8 : room;
        spot.name = clip(node.name(), nameFont, nameRoom);
        double nameWidth = width(spot.name, nameFont);
        double first = nameWidth;
        if (badgeOnName) {
            spot.nameBadge = node.badge();
            spot.nameBadgeX = nameWidth + 8;
            first = spot.nameBadgeX + width(node.badge(), DETAIL);
        }
        double second = 0;
        if (!node.detail().isEmpty()) {
            boolean badgeBelow = !node.badge().isEmpty();
            double badgeWidth = badgeBelow ? width("  " + node.badge(), DETAIL) : 0;
            if (badgeBelow && room - badgeWidth < 40) {
                // No room for both: the badge says more than the detail.
                spot.detailBadge = node.badge();
                spot.detailBadgeX = 0;
                second = width(node.badge(), DETAIL);
            } else {
                spot.detail = clip(node.detail(), DETAIL, room - badgeWidth);
                second = width(spot.detail, DETAIL);
                if (badgeBelow) {
                    spot.detailBadge = node.badge();
                    spot.detailBadgeX = second + width("  ", DETAIL);
                    second = spot.detailBadgeX + width(node.badge(), DETAIL);
                }
            }
        }
        spot.labelWidth = Math.max(first, second);
        spot.clipped = !spot.name.equals(node.name()) || (!node.detail().isEmpty() && !spot.detail.equals(node.detail()));
    }

    private static double memberX(Placed spot, int member) {
        int count = Math.max(1, spot.node.members().size());
        double inner = (count - 1) * MEMBER_GAP;
        return spot.x - inner / 2 + member * MEMBER_GAP;
    }

    // The pointer

    private Placed nodeAt(double x, double y) {
        for (Placed spot : placed.values()) {
            if (spot.hit(x, y)) {
                return spot;
            }
        }
        return null;
    }

    private void hover(MouseEvent event) {
        Placed at = nodeAt(event.getX(), event.getY());
        hovered = at == null ? null : at.node.key();
        setCursor(at != null && at.node.open() != null ? Cursor.HAND : Cursor.DEFAULT);
    }

    /** Everything a node is on the way of: what leads to it, and what it leads to. */
    private Set<String> chainOf(String key) {
        Set<String> found = new HashSet<>();
        found.add(key);
        Deque<String> up = new ArrayDeque<>(List.of(key));
        while (!up.isEmpty()) {
            for (Link link : incoming.getOrDefault(up.pop(), List.of())) {
                if (found.add(link.from())) {
                    up.push(link.from());
                }
            }
        }
        Deque<String> down = new ArrayDeque<>(List.of(key));
        Set<String> below = new HashSet<>();
        while (!down.isEmpty()) {
            for (Link link : outgoing.getOrDefault(down.pop(), List.of())) {
                if (below.add(link.to())) {
                    down.push(link.to());
                }
            }
        }
        found.addAll(below);
        return found;
    }

    // Drawing

    private static double now() {
        return System.nanoTime() / 1e9;
    }

    private double clock() {
        return frozen >= 0 ? shownAt + frozen : now();
    }

    private void paint() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setGlobalAlpha(1);
        gc.setLineDashes((double[]) null);
        gc.clearRect(0, 0, w, h);
        if (graph == null || w < 240 || h < 60) {
            return;
        }
        if (w != placedWidth || h != placedHeight) {
            place(w, h);
        }
        double t = clock();
        double since = t - shownAt;
        light(t);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.setTextBaseline(VPos.CENTER);
        gc.setTextAlign(TextAlignment.LEFT);
        drawBands(gc, w, since);
        drawLinks(gc, since, t);
        for (Placed spot : placed.values()) {
            drawNode(gc, spot, since, t);
        }
        drawNote(gc, w, since);
        drawTip(gc, w);
        gc.setGlobalAlpha(1);
        gc.setLineDashes((double[]) null);
    }

    /** Fades the light on the way being pointed at in and out. */
    private void light(double t) {
        double step = lastPaint < 0 ? 0 : Math.min(0.1, Math.max(0, t - lastPaint)) / 0.16;
        lastPaint = t;
        if (hovered != null && !hovered.equals(lit)) {
            lit = hovered;
            chain = chainOf(hovered);
        }
        focus = hovered != null ? Math.min(1, focus + step) : Math.max(0, focus - step);
        if (hovered == null && focus <= 0) {
            lit = null;
            chain = Set.of();
        }
    }

    private double dimOf(String key) {
        return chain.isEmpty() || chain.contains(key) ? 1 : 1 - 0.8 * focus;
    }

    private void drawBands(GraphicsContext gc, double w, double since) {
        for (int b = 0; b < bands.size(); b++) {
            Band band = bands.get(b);
            if (b % 2 == 1) {
                gc.setGlobalAlpha(1);
                gc.setFill(BAND_FILL);
                gc.fillRect(0, band.top(), w, band.height());
            }
            if (b > 0) {
                gc.setGlobalAlpha(1);
                gc.setStroke(RULE);
                gc.setLineWidth(1);
                double line = Math.floor(band.top()) + 0.5;
                gc.strokeLine(0, line, w, line);
            }
            double in = ease((since - b * STAGGER) / NODE_IN);
            double mid = band.top() + band.height() / 2;
            boolean counted = band.layer() != Layer.NGINX && band.layer() != Layer.MACHINE && band.count() > 0;
            gc.setGlobalAlpha(in * 0.9);
            gc.setFont(BAND);
            gc.setFill(KEY);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(band.layer().title, 16 - (1 - in) * 6, counted ? mid - 7 : mid);
            if (counted) {
                // A short hatch and the count under the name of the band.
                gc.setGlobalAlpha(in);
                gc.setStroke(FAINT);
                gc.setLineWidth(1.2);
                for (int s = 0; s < 4; s++) {
                    double x = 17 + s * 5;
                    gc.strokeLine(x, mid + 12, x + 4, mid + 4);
                }
                gc.setFont(DETAIL);
                gc.setFill(DIM);
                gc.fillText(String.valueOf(band.count()), 17 + 4 * 5 + 5, mid + 8);
            }
        }
    }

    private void drawLinks(GraphicsContext gc, double since, double t) {
        boolean flowing = graph.on() && !graph.broken();
        double fade = graph.on() ? 1 : 0.5;
        for (Link link : graph.links()) {
            Placed a = placed.get(link.from());
            Placed b = placed.get(link.to());
            if (a == null || b == null) {
                continue;
            }
            double grow = ease((since - a.band * STAGGER - 0.16) / LINK_IN);
            if (grow <= 0) {
                continue;
            }
            double[] from = exit(a, link.member());
            double[] to = {b.x, b.y - b.halfHeight - 1};
            boolean onWay = lit != null && chain.contains(a.node.key()) && chain.contains(b.node.key());
            double dim = Math.min(dimOf(a.node.key()), dimOf(b.node.key()));
            boolean broken = b.node.state() == State.BROKEN;
            Color top = tone(a.node);
            Color bottom = tone(b.node);
            gc.setGlobalAlpha(fade * dim * (onWay ? 0.5 + 0.45 * focus : 0.5));
            gc.setStroke(new LinearGradient(0, from[1], 0, to[1], false, CycleMethod.NO_CYCLE,
                    new Stop(0, top), new Stop(1, bottom)));
            gc.setLineWidth(onWay ? 1.2 + 0.7 * focus : 1.2);
            if (broken) {
                gc.setLineDashes(4, 5);
            }
            curve(gc, from, to, grow);
            gc.setLineDashes((double[]) null);
            if (grow >= 1 && flowing && !broken) {
                particles(gc, from, to, top, bottom, t, link, fade * dim);
            }
        }
    }

    /** Two specks of light running down a link, the requests it carries, each a little behind the other. */
    private static void particles(GraphicsContext gc, double[] from, double[] to, Color top, Color bottom, double t,
                                  Link link, double alpha) {
        double seed = (link.hashCode() & 0xffff) / 65535.0;
        for (int k = 0; k < 2; k++) {
            double phase = (t / TRAVEL + seed + k * 0.5) % 1;
            double[] at = point(from, to, phase);
            Color colour = top.interpolate(bottom, phase);
            double life = Math.sin(Math.PI * phase);
            gc.setFill(colour);
            gc.setGlobalAlpha(alpha * 0.2 * life);
            gc.fillOval(at[0] - 4.5, at[1] - 4.5, 9, 9);
            gc.setGlobalAlpha(alpha * 0.95 * life);
            gc.fillOval(at[0] - 1.6, at[1] - 1.6, 3.2, 3.2);
        }
    }

    private static double[] exit(Placed spot, int member) {
        if (spot.node.kind() == Kind.POOL && member >= 0) {
            return new double[]{memberX(spot, member), spot.y + spot.halfHeight + 1};
        }
        return new double[]{spot.x, spot.y + spot.halfHeight + 1};
    }

    private void drawNode(GraphicsContext gc, Placed spot, double since, double t) {
        double in = ease((since - spot.band * STAGGER) / NODE_IN);
        if (in <= 0) {
            return;
        }
        Node node = spot.node;
        boolean hot = node.key().equals(hovered);
        double alpha = in * dimOf(node.key()) * (graph.on() || node.kind() == Kind.NGINX ? 1 : 0.55);
        double scale = 0.55 + 0.45 * in;
        Color colour = tone(node);
        double x = spot.x;
        double y = spot.y;

        // A soft light under every mark, breathing on Nginx while it serves the site.
        double halo = (Math.max(spot.halfWidth, spot.halfHeight) + 12) * scale * (hot ? 1.3 : 1);
        double breath = node.kind() == Kind.NGINX && graph.on() && !graph.broken() ? 0.07 * Math.sin(t * 2.1) : 0;
        gc.setGlobalAlpha(Math.max(0, alpha * ((hot ? 0.3 : 0.13) + breath)));
        gc.setFill(new RadialGradient(0, 0, x, y, halo, false, CycleMethod.NO_CYCLE,
                new Stop(0, colour), new Stop(1, Color.TRANSPARENT)));
        gc.fillOval(x - halo, y - halo, halo * 2, halo * 2);

        gc.setGlobalAlpha(alpha);
        gc.setStroke(colour);
        gc.setLineWidth(1.3);
        switch (node.kind()) {
            case DOMAIN -> globe(gc, x, y, 7 * scale, colour);
            case NGINX -> nginx(gc, x, y, 13 * scale, colour, alpha, t);
            case PATH -> {
                ring(gc, x, y, 7 * scale, colour);
                gc.strokeLine(x - 2.2 * scale, y + 3.4 * scale, x + 2.2 * scale, y - 3.4 * scale);
            }
            case PORT -> {
                ring(gc, x, y, 7 * scale, colour);
                if (node.state() == State.BROKEN) {
                    cross(gc, x, y, 2.6 * scale);
                } else {
                    gc.setFill(colour);
                    gc.fillOval(x - 2.6 * scale, y - 2.6 * scale, 5.2 * scale, 5.2 * scale);
                }
            }
            case POOL -> pool(gc, spot, scale, colour, alpha);
            case FOLDER -> folder(gc, x, y, scale, colour, alpha);
            case REDIRECT -> {
                ring(gc, x, y, 7 * scale, colour);
                arrow(gc, x - 3 * scale, y, x + 3.4 * scale, y, 2.4 * scale);
            }
            case ANSWER -> {
                ring(gc, x, y, 7 * scale, colour);
                gc.strokeLine(x - 3 * scale, y - 1.6 * scale, x + 3 * scale, y - 1.6 * scale);
                gc.strokeLine(x - 3 * scale, y + 1.6 * scale, x + 3 * scale, y + 1.6 * scale);
            }
            case REMOTE -> {
                gc.setLineDashes(2.5, 2.5);
                ring(gc, x, y, 7 * scale, colour);
                gc.setLineDashes((double[]) null);
                arrow(gc, x - 1 * scale, y + 1 * scale, x + 5 * scale, y - 5 * scale, 2.4 * scale);
            }
            case PROGRAM -> program(gc, x, y, scale, colour, alpha);
            case MACHINE -> machine(gc, x, y, scale, colour, alpha, t);
        }

        if (hot) {
            gc.setGlobalAlpha(alpha * 0.85);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1);
            double rx = spot.halfWidth * scale + 5;
            double ry = spot.halfHeight * scale + 5;
            if (node.kind() == Kind.POOL || node.kind() == Kind.MACHINE) {
                gc.strokeRoundRect(x - rx, y - ry, rx * 2, ry * 2, 12, 12);
            } else {
                gc.strokeOval(x - rx, y - ry, rx * 2, ry * 2);
            }
        }
        // Where the way stops on something broken, which is where every visitor sent there stops too.
        if (node.state() == State.BROKEN && in >= 1 && node.kind() != Kind.NGINX && node.kind() != Kind.POOL
                && outgoing.getOrDefault(node.key(), List.of()).isEmpty()) {
            dead(gc, x, y + spot.halfHeight, spot.halfWidth, alpha, t);
        }
        drawLabel(gc, spot, alpha, in, hot);
    }

    /** Where a request falls: a ring going out from the mark, and a dashed drop to a cross under it. */
    private static void dead(GraphicsContext gc, double x, double bottom, double radius, double alpha, double t) {
        Color red = State.BROKEN.colour;
        double phase = (t * 0.6) % 1;
        double r = radius + 3 + phase * 11;
        gc.setGlobalAlpha(alpha * (1 - phase) * 0.45);
        gc.setStroke(red);
        gc.setLineWidth(1);
        gc.strokeOval(x - r, bottom - radius - r, r * 2, r * 2);
        drop(gc, x, bottom, alpha);
    }

    private static void drop(GraphicsContext gc, double x, double bottom, double alpha) {
        Color red = State.BROKEN.colour;
        gc.setGlobalAlpha(alpha * 0.8);
        gc.setStroke(red);
        gc.setLineWidth(1.1);
        gc.setLineDashes(2.5, 3);
        gc.strokeLine(x, bottom + 3, x, bottom + 17);
        gc.setLineDashes((double[]) null);
        cross(gc, x, bottom + 21, 2.8);
    }

    private void drawLabel(GraphicsContext gc, Placed spot, double alpha, double in, boolean hot) {
        if (spot.labelWidth <= 0) {
            return;
        }
        Node node = spot.node;
        double x = spot.x + spot.halfWidth + 10 + (1 - in) * 8;
        boolean twoLines = !spot.detail.isEmpty() || !spot.detailBadge.isEmpty();
        double nameY = twoLines ? spot.y - 7 : spot.y;
        Font nameFont = node.kind() == Kind.NGINX ? TITLE : NAME;
        gc.setGlobalAlpha(alpha);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(nameFont);
        gc.setFill(node.kind() == Kind.NGINX ? tone(node) : hot ? Color.WHITE : TEXT);
        gc.fillText(spot.name, x, nameY);
        if (!spot.nameBadge.isEmpty()) {
            gc.setFont(DETAIL);
            gc.setFill(node.badgeState().colour);
            gc.fillText(spot.nameBadge, x + spot.nameBadgeX, nameY + 0.5);
        }
        if (twoLines) {
            gc.setFont(DETAIL);
            gc.setFill(node.state() == State.BROKEN ? State.BROKEN.colour : DIM);
            gc.fillText(spot.detail, x, spot.y + 8);
            if (!spot.detailBadge.isEmpty()) {
                gc.setFill(node.badgeState().colour);
                gc.fillText(spot.detailBadge, x + spot.detailBadgeX, spot.y + 8);
            }
        }
    }

    private void drawNote(GraphicsContext gc, double w, double since) {
        if (graph.note().isEmpty() || countIn(Layer.PATHS) > 0) {
            return;
        }
        for (int b = 0; b < bands.size(); b++) {
            Band band = bands.get(b);
            if (band.layer() == Layer.PATHS) {
                double left = graphLeft(w);
                gc.setGlobalAlpha(ease((since - b * STAGGER) / NODE_IN));
                gc.setFont(NAME);
                gc.setFill(MUTED);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText(clip(graph.note(), NAME, w - left - 30), left + (w - 18 - left) / 2,
                        band.top() + band.height() / 2);
                gc.setTextAlign(TextAlignment.LEFT);
            }
        }
    }

    /** The whole name of the thing pointed at, when the room beside it cut it short. */
    private void drawTip(GraphicsContext gc, double w) {
        Placed spot = hovered == null ? null : placed.get(hovered);
        if (spot == null || !spot.clipped) {
            return;
        }
        Node node = spot.node;
        String second = (node.detail() + (node.badge().isEmpty() ? "" : "  " + node.badge())).strip();
        double boxWidth = Math.min(w - 12, Math.max(width(node.name(), NAME), width(second, DETAIL)) + 22);
        double boxHeight = second.isEmpty() ? 26 : 42;
        double x = Math.max(6, Math.min(spot.x - spot.halfWidth, w - boxWidth - 6));
        double y = spot.y - spot.halfHeight - boxHeight - 10;
        if (y < 4) {
            y = spot.y + spot.halfHeight + 10;
        }
        gc.setGlobalAlpha(0.97);
        gc.setFill(TIP);
        gc.fillRoundRect(x, y, boxWidth, boxHeight, 8, 8);
        gc.setStroke(FAINT);
        gc.setLineWidth(1);
        gc.strokeRoundRect(x + 0.5, y + 0.5, boxWidth - 1, boxHeight - 1, 8, 8);
        gc.setGlobalAlpha(1);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(NAME);
        gc.setFill(TEXT);
        gc.fillText(clip(node.name(), NAME, boxWidth - 20), x + 11, y + 13);
        if (!second.isEmpty()) {
            gc.setFont(DETAIL);
            gc.setFill(node.state() == State.BROKEN ? State.BROKEN.colour : DIM);
            gc.fillText(clip(second, DETAIL, boxWidth - 20), x + 11, y + 29);
        }
    }

    // Marks

    private static void ring(GraphicsContext gc, double x, double y, double r, Color colour) {
        gc.setFill(DARK);
        gc.fillOval(x - r, y - r, r * 2, r * 2);
        gc.setStroke(colour);
        gc.strokeOval(x - r, y - r, r * 2, r * 2);
    }

    /** A name on the internet: a small globe. */
    private static void globe(GraphicsContext gc, double x, double y, double r, Color colour) {
        ring(gc, x, y, r, colour);
        double alpha = gc.getGlobalAlpha();
        gc.setGlobalAlpha(alpha * 0.7);
        gc.setLineWidth(1);
        gc.strokeOval(x - r * 0.45, y - r, r * 0.9, r * 2);
        gc.strokeLine(x - r, y, x + r, y);
        gc.setGlobalAlpha(alpha);
        gc.setLineWidth(1.3);
    }

    private void nginx(GraphicsContext gc, double x, double y, double r, Color colour, double alpha, double t) {
        boolean serving = graph.on() && !graph.broken();
        if (serving) {
            double swell = r + 5 + 1.6 * Math.sin(t * 2.1);
            gc.setGlobalAlpha(alpha * (0.18 + 0.1 * Math.sin(t * 2.1)));
            gc.setLineWidth(1);
            hexagon(gc, x, y, swell);
            gc.stroke();
        }
        gc.setGlobalAlpha(alpha);
        hexagon(gc, x, y, r);
        gc.setFill(DARK);
        gc.fill();
        gc.setGlobalAlpha(alpha * 0.16);
        gc.setFill(colour);
        gc.fill();
        gc.setGlobalAlpha(alpha);
        gc.setStroke(colour);
        gc.setLineWidth(1.6);
        if (!graph.on()) {
            gc.setLineDashes(4, 3);
        }
        gc.stroke();
        gc.setLineDashes((double[]) null);
        hexagon(gc, x, y, r * 0.4);
        gc.setFill(colour);
        gc.fill();
        gc.setLineWidth(1.3);
    }

    private static void hexagon(GraphicsContext gc, double x, double y, double r) {
        gc.beginPath();
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(-90 + 60 * i);
            double px = x + r * Math.cos(angle);
            double py = y + r * Math.sin(angle);
            if (i == 0) {
                gc.moveTo(px, py);
            } else {
                gc.lineTo(px, py);
            }
        }
        gc.closePath();
    }

    /** A pool: a dashed capsule with one dot per server, lit when something answers on it. */
    private static void pool(GraphicsContext gc, Placed spot, double scale, Color colour, double alpha) {
        double halfWidth = spot.halfWidth * scale;
        double halfHeight = spot.halfHeight * scale;
        double x = spot.x;
        double y = spot.y;
        gc.setFill(DARK);
        gc.fillRoundRect(x - halfWidth, y - halfHeight, halfWidth * 2, halfHeight * 2, halfHeight * 2,
                halfHeight * 2);
        gc.setGlobalAlpha(alpha * 0.1);
        gc.setFill(colour);
        gc.fillRoundRect(x - halfWidth, y - halfHeight, halfWidth * 2, halfHeight * 2, halfHeight * 2,
                halfHeight * 2);
        gc.setGlobalAlpha(alpha);
        gc.setStroke(colour);
        gc.setLineWidth(1.1);
        gc.setLineDashes(3, 3);
        gc.strokeRoundRect(x - halfWidth, y - halfHeight, halfWidth * 2, halfHeight * 2, halfHeight * 2,
                halfHeight * 2);
        gc.setLineDashes((double[]) null);
        List<State> members = spot.node.members();
        for (int m = 0; m < members.size(); m++) {
            double mx = x + (memberX(spot, m) - x) * scale;
            double r = 4 * scale;
            State member = members.get(m);
            gc.setGlobalAlpha(alpha);
            switch (member) {
                case WORKS -> {
                    gc.setFill(State.WORKS.colour);
                    gc.fillOval(mx - r, y - r, r * 2, r * 2);
                }
                case BROKEN -> {
                    gc.setStroke(State.BROKEN.colour);
                    gc.setLineWidth(1.2);
                    gc.strokeOval(mx - r, y - r, r * 2, r * 2);
                    cross(gc, mx, y, r * 0.55);
                    if (scale >= 1) {
                        drop(gc, mx, y + halfHeight, alpha);
                    }
                }
                default -> {
                    gc.setStroke(State.UNKNOWN.colour);
                    gc.setLineWidth(1);
                    gc.strokeOval(mx - r, y - r, r * 2, r * 2);
                }
            }
        }
        gc.setLineWidth(1.3);
    }

    private static void folder(GraphicsContext gc, double x, double y, double scale, Color colour, double alpha) {
        double w = 9 * scale;
        double h = 7 * scale;
        gc.beginPath();
        gc.moveTo(x - w, y - h + 2.5 * scale);
        gc.lineTo(x - w, y + h);
        gc.lineTo(x + w, y + h);
        gc.lineTo(x + w, y - h + 2.5 * scale);
        gc.lineTo(x - 0.5 * scale, y - h + 2.5 * scale);
        gc.lineTo(x - 2.5 * scale, y - h);
        gc.lineTo(x - w, y - h);
        gc.closePath();
        gc.setFill(DARK);
        gc.fill();
        gc.setGlobalAlpha(alpha * 0.18);
        gc.setFill(colour);
        gc.fill();
        gc.setGlobalAlpha(alpha);
        gc.setStroke(colour);
        gc.stroke();
    }

    /** A program: a small window with a prompt in it. */
    private static void program(GraphicsContext gc, double x, double y, double scale, Color colour, double alpha) {
        double r = 8 * scale;
        gc.setFill(DARK);
        gc.fillRoundRect(x - r, y - r, r * 2, r * 2, 5, 5);
        gc.setGlobalAlpha(alpha * 0.16);
        gc.setFill(colour);
        gc.fillRoundRect(x - r, y - r, r * 2, r * 2, 5, 5);
        gc.setGlobalAlpha(alpha);
        gc.setStroke(colour);
        gc.strokeRoundRect(x - r, y - r, r * 2, r * 2, 5, 5);
        gc.setLineWidth(1.1);
        gc.strokeLine(x - 4 * scale, y - 2.5 * scale, x - 1.5 * scale, y);
        gc.strokeLine(x - 1.5 * scale, y, x - 4 * scale, y + 2.5 * scale);
        gc.strokeLine(x, y + 2.8 * scale, x + 4 * scale, y + 2.8 * scale);
        gc.setLineWidth(1.3);
    }

    /** This machine: a small rack, its lights blinking out of step. */
    private static void machine(GraphicsContext gc, double x, double y, double scale, Color colour, double alpha,
                                double t) {
        double w = 15 * scale;
        double h = 11 * scale;
        gc.setFill(DARK);
        gc.fillRoundRect(x - w, y - h, w * 2, h * 2, 4, 4);
        gc.setGlobalAlpha(alpha * 0.08);
        gc.setFill(colour);
        gc.fillRoundRect(x - w, y - h, w * 2, h * 2, 4, 4);
        gc.setGlobalAlpha(alpha);
        gc.setStroke(colour);
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(x - w, y - h, w * 2, h * 2, 4, 4);
        for (int slot = 0; slot < 3; slot++) {
            double sy = y - h + (slot + 0.5) * (h * 2 / 3);
            gc.setGlobalAlpha(alpha * 0.45);
            gc.setLineWidth(1);
            gc.strokeLine(x - w + 4, sy, x + w - 9, sy);
            double blink = 0.5 + 0.5 * Math.sin(t * (2.2 + slot * 0.9) + slot * 1.7);
            gc.setGlobalAlpha(alpha * (0.35 + 0.65 * blink));
            gc.setFill(State.WORKS.colour);
            gc.fillOval(x + w - 6, sy - 1.3, 2.6, 2.6);
        }
        gc.setGlobalAlpha(alpha);
        gc.setLineWidth(1.3);
    }

    private static void cross(GraphicsContext gc, double x, double y, double r) {
        gc.setStroke(State.BROKEN.colour);
        gc.strokeLine(x - r, y - r, x + r, y + r);
        gc.strokeLine(x - r, y + r, x + r, y - r);
    }

    private static void arrow(GraphicsContext gc, double x1, double y1, double x2, double y2, double head) {
        gc.strokeLine(x1, y1, x2, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        gc.strokeLine(x2, y2, x2 - head * Math.cos(angle - 0.6), y2 - head * Math.sin(angle - 0.6));
        gc.strokeLine(x2, y2, x2 - head * Math.cos(angle + 0.6), y2 - head * Math.sin(angle + 0.6));
    }

    /** The colour a node is drawn in: what it is, unless something is wrong with it. */
    private static Color tone(Node node) {
        if (node.state() == State.BROKEN || node.state() == State.WARNS) {
            return node.state().colour;
        }
        return switch (node.kind()) {
            case DOMAIN -> MAGENTA;
            case PROGRAM -> node.state() == State.UNKNOWN ? State.UNKNOWN.colour : VIOLET;
            case MACHINE -> SILVER;
            default -> node.state().colour;
        };
    }

    // Geometry

    /** An S from one mark down to the next, drawn as far as it has grown. */
    private static void curve(GraphicsContext gc, double[] from, double[] to, double grown) {
        gc.beginPath();
        gc.moveTo(from[0], from[1]);
        if (grown >= 1) {
            double middle = (to[1] - from[1]) / 2;
            gc.bezierCurveTo(from[0], from[1] + middle, to[0], to[1] - middle, to[0], to[1]);
        } else {
            int steps = 30;
            for (int i = 1; i <= steps; i++) {
                double s = Math.min(grown, i / (double) steps);
                double[] at = point(from, to, s);
                gc.lineTo(at[0], at[1]);
                if (s >= grown) {
                    break;
                }
            }
        }
        gc.stroke();
    }

    private static double[] point(double[] from, double[] to, double s) {
        double middle = (to[1] - from[1]) / 2;
        double c1x = from[0];
        double c1y = from[1] + middle;
        double c2x = to[0];
        double c2y = to[1] - middle;
        double u = 1 - s;
        double x = u * u * u * from[0] + 3 * u * u * s * c1x + 3 * u * s * s * c2x + s * s * s * to[0];
        double y = u * u * u * from[1] + 3 * u * u * s * c1y + 3 * u * s * s * c2y + s * s * s * to[1];
        return new double[]{x, y};
    }

    private static double ease(double value) {
        double v = Math.max(0, Math.min(1, value));
        return 1 - Math.pow(1 - v, 3);
    }

    private static double width(String text, Font font) {
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
    }

    private static String clip(String text, Font font, double room) {
        if (room <= 14) {
            return "";
        }
        if (width(text, font) <= room) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && width(cut + "…", font) > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
