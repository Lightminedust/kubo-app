package com.kubo.ui.docker;

import com.kubo.model.docker.DockerNetwork;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;
import java.util.Locale;

/**
 * The Docker networks and which containers share each one, so it is clear what can talk to what.
 */
final class NetworksView extends VBox {

    private final VBox rows = new VBox(10);

    NetworksView() {
        getStyleClass().add("scope-view");
        setPadding(new Insets(24, 40, 24, 40));
        setSpacing(14);

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(label("NETWORKS", "series"), scroll);
    }

    /**
     * Clears the list and shows that it is being read again.
     */
    void showLoading() {
        rows.getChildren().setAll(label("READING NETWORKS…", "metric-label"));
    }

    void setNetworks(List<DockerNetwork> networks) {
        rows.getChildren().clear();
        if (networks.isEmpty()) {
            rows.getChildren().add(label("NO NETWORKS.", "metric-label"));
            return;
        }
        for (DockerNetwork network : networks) {
            rows.getChildren().add(card(network));
        }
    }

    private VBox card(DockerNetwork network) {
        Circle dot = new Circle(4, network.isBuiltIn() ? Color.web("#6e6e7d") : Color.web("#8a4dff"));
        Label name = label(network.name(), "mono");
        Label driver = label(network.driver().toUpperCase(Locale.ROOT), "badge");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = label(network.containers().size() + (network.containers().size() == 1
                ? " CONTAINER" : " CONTAINERS"), "metric-label");
        HBox head = new HBox(10, dot, name, driver, spacer, count);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, head);
        if (!network.containers().isEmpty()) {
            FlowPane chips = new FlowPane(8, 6);
            for (String container : network.containers()) {
                chips.getChildren().add(label(container, "net-chip"));
            }
            card.getChildren().add(chips);
        }
        card.getStyleClass().add("network-card");
        card.setPadding(new Insets(12, 16, 12, 14));
        return card;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
