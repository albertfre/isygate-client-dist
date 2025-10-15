package it.isygate.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.control.ContentDisplay;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.FadeTransition;
import javafx.animation.KeyValue;
import javafx.util.Duration;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.RowConstraints;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.layout.Region;
import javafx.scene.layout.Pane;
import javafx.geometry.Rectangle2D;
import javafx.scene.shape.Rectangle;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tooltip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.*;
import java.io.*;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.Cursor;

import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.Executors;

public class ClientApp extends Application {

    // ===== rete =====
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final Gson gson = new Gson();

    // 🔹 Gestione riconnessione automatica
    private String currentHost = "81.56.92.209";
    private int currentPort = 17000;
    private String sessionToken = null; // token di sessione per re-auth

    // === Campi per effetto luminoso sull'area di output ===
    private VirtualizedScrollPane<StyleClassedTextArea> outputScroll;
    private StackPane outputWrapper;

    // Flag di chiusura
    private volatile boolean closing = false;

    // ===== UI principali =====
    private StyleClassedTextArea outputArea; // RichTextFX
    private TextField inputField;
    private Stage primary;

    // preserva storico e stato UI
    private boolean uiInitialized = false;
    private final StringBuilder logBuf = new StringBuilder(8192);

    // per ripristino senza reinserire nel buffer
    private boolean renderingFromBuffer = false;

    // de-dup mirato per messaggi di connessione
    private String lastStatusLine = null;

    // Campo a livello di classe
    private final java.util.concurrent.atomic.AtomicBoolean readerRunning = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    // mappa "indice paragrafo" -> grafica (icona) da mostrare a sinistra del
    // paragrafo
    private final java.util.Map<Integer, javafx.scene.Node> paragraphIconMap = new java.util.HashMap<>();

    private static final int NAV_BTN = 44; // dimensione quadrata dei bottoni
    private static final int NAV_BTN_CENTER = 52; // più grande per l'occhio

    private Button makeNavButton(String text) {
        Button b = new Button(text);
        b.setPrefSize(NAV_BTN, NAV_BTN);
        b.setMinSize(NAV_BTN, NAV_BTN);
        b.setMaxSize(NAV_BTN, NAV_BTN);
        b.setFocusTraversable(false);
        b.setStyle(
                "-fx-background-color:#2b2b2b;" +
                        "-fx-text-fill:#e0e0c0;" +
                        "-fx-background-radius:10;" +
                        "-fx-border-color:#caa85a;" +
                        "-fx-border-width:2;" +
                        "-fx-border-radius:10;");
        return b;
    }

    // ===== TEMPO E METEO =====
    private Label timeLabel;
    private ImageView weatherIcon;

    // 🔹 Nuova unica immagine per fase ciclica (sole/luna)
    private ImageView cycleImage;

    // ===== HUD =====
    private ProgressBar healthBar;
    private ProgressBar staminaBar;
    private ProgressBar hungerBar;
    private ProgressBar thirstBar;

    // ===== MINIMAP =====
    private GridPane minimapGrid; // ortho (overworld)
    private Pane minimapPane; // iso (città/dungeon)
    private boolean isoMode = false;
    private String currentMapName = "overworld";

    private final int TILE_SIZE = 19; // lato tile base (px)
    private final int VIEW_SIZE = 6; // finestra (6x6)
    private final double MINIMAP_ZOOM = 1.5;
    private int currentViewSize = VIEW_SIZE;

    // mappa celle solo per l'iso
    private final Map<String, StackPane> isoCells = new java.util.HashMap<>();

    private final Map<String, Image> tileCache = new java.util.HashMap<>(); // cache immagini tile

    // --- Viewport 45x45 stile Isylea ---
    private static final int ISO_SPRITE_SIZE = 45;
    private static final int ISO_TILE_SIZE = 19;
    private static final int ISO_MARGIN = (ISO_SPRITE_SIZE - ISO_TILE_SIZE) / 2; // 13

    // Pavimento: quadrato centrale 19x19 a (13,13)
    private static final javafx.geometry.Rectangle2D VP_FLOOR = new javafx.geometry.Rectangle2D(ISO_MARGIN, ISO_MARGIN,
            ISO_TILE_SIZE, ISO_TILE_SIZE);

    // Muri: strisce attorno al riquadro centrale
    private static final javafx.geometry.Rectangle2D VP_WALL_N = new javafx.geometry.Rectangle2D(ISO_MARGIN, 0,
            ISO_TILE_SIZE, ISO_MARGIN); // 19x13 sopra
    private static final javafx.geometry.Rectangle2D VP_WALL_S = new javafx.geometry.Rectangle2D(ISO_MARGIN,
            ISO_SPRITE_SIZE - ISO_MARGIN, ISO_TILE_SIZE, ISO_MARGIN); // 19x13 sotto
    private static final javafx.geometry.Rectangle2D VP_WALL_E = new javafx.geometry.Rectangle2D(
            ISO_SPRITE_SIZE - ISO_MARGIN, ISO_MARGIN, ISO_MARGIN, ISO_TILE_SIZE); // 13x19 dx
    private static final javafx.geometry.Rectangle2D VP_WALL_O = new javafx.geometry.Rectangle2D(0, ISO_MARGIN,
            ISO_MARGIN, ISO_TILE_SIZE); // 13x19 sx

    // private final double MINIMAP_ZOOM = 2.0; // ingrandisce ogni tile

    private Label healthLbl;
    private Label staminaLbl;
    private Label hungerLbl;
    private Label thirstLbl;

    // percentuali in overlay
    private Label healthPct;
    private Label staminaPct;
    private Label hungerPct;
    private Label thirstPct;

    // Stato ambiente/piani
    private int playerFloor = 0;
    private boolean isOutdoor = true; // outdoor = true => tetti visibili (in seguito)
    private boolean firstConnection = true;

    // ===== Movimento =====
    private String movementSpeed = null; // ultimo valore noto
    private String lastPrintedSpeed = null; // per evitare stampe duplicate

    @Override
    public void start(Stage stage) {
        TileRegistry.load();
        this.primary = stage;

        // ✅ Intercetta il click sulla ❌ e avvia il countdown identico al pulsante Exit
        this.primary.setOnCloseRequest(ev -> {
            ev.consume(); // ❗ Blocca la chiusura immediata

            Stage dialog = new Stage();
            dialog.setTitle("Uscita dal gioco");

            Label lbl = new Label("Disconnessione in 5 secondi...");
            lbl.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 16px;");

            // Timeline referenziabile anche dentro il pulsante "Annulla"
            final Timeline[] timelineRef = new Timeline[1];

            Button cancelBtn = new Button("Annulla");
            cancelBtn.setOnAction(evc -> {
                if (timelineRef[0] != null) {
                    timelineRef[0].stop(); // ✅ ferma il countdown!
                }
                dialog.close();
                System.out.println("[ClientApp] Countdown annullato: finestra non chiusa.");
            });

            VBox box = new VBox(15, lbl, cancelBtn);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(20));
            box.setStyle("-fx-background-color: #1b1b1b;");

            Scene sc = new Scene(box, 320, 150);
            dialog.setScene(sc);
            dialog.initOwner(primary);
            dialog.setResizable(false);
            dialog.show();

            final int[] counter = { 5 };
            Timeline timeline = new Timeline(
                    new KeyFrame(Duration.seconds(1), evc -> {
                        counter[0]--;
                        if (counter[0] > 0) {
                            lbl.setText("Disconnessione in " + counter[0] + " secondi...");
                        } else {
                            dialog.close();
                            closing = true;
                            try {
                                if (out != null) {
                                    JsonObject cmd = new JsonObject();
                                    cmd.addProperty("type", "cmd");
                                    cmd.addProperty("text", "exit");
                                    out.println(gson.toJson(cmd));
                                }
                                if (socket != null && !socket.isClosed())
                                    socket.close();
                            } catch (Exception ignored) {
                            }
                            Platform.runLater(() -> primary.close());
                        }
                    }));
            timeline.setCycleCount(5);
            timeline.play();

            timelineRef[0] = timeline; // ✅ salva riferimento
        });

        showLogin();
        checkResources();
    }

    // =============================================================================================
    // Login
    // =============================================================================================
    private void showLogin() {
        // === Lettura credenziali salvate ===
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(ClientApp.class);
        String savedUser = prefs.get("username", "");
        String savedPass = prefs.get("password", "");

        // ✅ Non azzerare sempre il token — fallo solo se l’utente apre manualmente il
        // login
        if (closing) {
            sessionToken = null;
            closing = false;
        }

        Label lUser = new Label("Username:");
        TextField tfUser = new TextField(savedUser);

        Label lPass = new Label("Password:");
        PasswordField pf = new PasswordField();
        pf.setText(savedPass);

        Button btn = new Button("Accedi / Registrati");

        VBox v = new VBox(10, lUser, tfUser, lPass, pf, btn);
        v.setPadding(new Insets(15));
        v.setStyle("-fx-background-color: #1b1b1b; -fx-text-fill: #e0e0c0;");

        Scene scene = new Scene(v, 400, 250);
        var css = getClass().getResource("/dark.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        primary.setTitle("isygate - login");
        primary.setScene(scene);
        primary.show();
        primary.centerOnScreen();

        // === Sezione "Verifica aggiornamenti" ===
        Label lblUpdate = new Label("Verifica aggiornamenti...");
        lblUpdate.setStyle("-fx-text-fill: gray; -fx-underline: true;");
        lblUpdate.setCursor(Cursor.HAND);

        // Lo mettiamo in basso a destra della scena di login
        StackPane overlay = new StackPane(lblUpdate);
        overlay.setAlignment(Pos.BOTTOM_RIGHT);
        overlay.setPadding(new Insets(5));

        // Componiamo insieme layout principale + overlay
        StackPane rootPane = new StackPane();
        rootPane.getChildren().addAll(v, overlay);
        scene.setRoot(rootPane);

        // Avvia controllo aggiornamenti
        checkForUpdates(lblUpdate);

        // Enter invia
        pf.setOnKeyPressed(k -> {
            if (k.getCode() == KeyCode.ENTER)
                btn.fire();
        });
        tfUser.setOnKeyPressed(k -> {
            if (k.getCode() == KeyCode.ENTER)
                btn.fire();
        });

        btn.setOnAction(ev -> {
            String user = tfUser.getText().trim();
            String pass = pf.getText().trim();

            if (user.isEmpty() || pass.isEmpty()) {
                alert("username e password richiesti");
                return;
            }

            // 🔹 Salva le credenziali localmente
            prefs.put("username", user);
            prefs.put("password", pass);

            Executors.newSingleThreadExecutor().submit(() -> {
                try {
                    connectToServer("81.56.92.209", 17000);

                    if (socket == null || out == null) {
                        showErrorOnUI("Connessione non disponibile. Riprova tra qualche secondo.");
                        return;
                    }

                    JsonObject auth = new JsonObject();
                    auth.addProperty("type", "auth");
                    auth.addProperty("username", user);
                    auth.addProperty("password", pass);

                    // 🔹 se hai un token salvato, invialo per tentare auto-login
                    if (sessionToken != null && !sessionToken.isBlank()) {
                        auth.addProperty("token", sessionToken);
                    }

                    out.println(gson.toJson(auth));

                    // attendi risposta auth
                    String line;
                    JsonObject resp = null;

                    while ((line = in.readLine()) != null) {
                        JsonObject msg = gson.fromJson(line, JsonObject.class);
                        if (msg == null)
                            continue;

                        String type = msg.has("type") ? msg.get("type").getAsString() : "";
                        if ("auth".equals(type)) {
                            resp = msg;
                            break;
                        } else {
                            JsonObject finalMsg = msg;
                            Platform.runLater(() -> handleServerMessage(finalMsg));
                        }
                    }

                    if (resp == null) {
                        showErrorOnUI("Connessione chiusa dal server");
                        return;
                    }

                    if (resp.get("ok").getAsBoolean()) {
                        if (resp.has("token")) {
                            sessionToken = resp.get("token").getAsString();
                        }

                        firstConnection = false; // ✅ segna che la connessione iniziale è fatta

                        Platform.runLater(() -> {
                            buildMainUI();
                            startReaderLoop();
                        });

                    } else {
                        String msg = resp.has("message") ? resp.get("message").getAsString() : "Auth fallita";
                        showErrorOnUI(msg);
                    }

                } catch (Exception ex) {
                    showErrorOnUI("Errore: " + ex.getMessage());
                }
            });
        });
    }

    // =============================================================================================
    // UI principale (HUD + output + input)
    // =============================================================================================

    private void buildMainUI() {
        // === Barre vitali ===
        healthBar = makeBar("#ff3b30"); // rosso
        hungerBar = makeBar("#ff9500"); // arancio
        staminaBar = makeBar("#9acd32"); // verde
        thirstBar = makeBar("#6495ed"); // blu

        healthLbl = makeStatLabel("Salute");
        hungerLbl = makeStatLabel("Fame  ");
        staminaLbl = makeStatLabel("Fiato");
        thirstLbl = makeStatLabel("Sete ");

        healthPct = makePctLabel();
        staminaPct = makePctLabel();
        hungerPct = makePctLabel();
        thirstPct = makePctLabel();

        GridPane statsPane = new GridPane();
        statsPane.setHgap(16);
        statsPane.setVgap(6);
        statsPane.setPadding(new Insets(8, 8, 0, 8));

        // === colonne proporzionali ===
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50);
        c1.setHalignment(HPos.LEFT);

        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(50);
        c2.setHalignment(HPos.LEFT);

        statsPane.getColumnConstraints().addAll(c1, c2);

        // === righe ===
        HBox leftTop = makeHudRow(healthLbl, healthBar, healthPct);
        HBox rightTop = makeHudRow(staminaLbl, staminaBar, staminaPct);
        HBox leftBottom = makeHudRow(hungerLbl, hungerBar, hungerPct);
        HBox rightBottom = makeHudRow(thirstLbl, thirstBar, thirstPct);

        statsPane.add(leftTop, 0, 0);
        statsPane.add(rightTop, 1, 0);
        statsPane.add(leftBottom, 0, 1);
        statsPane.add(rightBottom, 1, 1);

        // 👇 forza espansione orizzontale dopo l’aggiunta
        GridPane.setHgrow(leftTop, Priority.ALWAYS);
        GridPane.setHgrow(rightTop, Priority.ALWAYS);
        GridPane.setHgrow(leftBottom, Priority.ALWAYS);
        GridPane.setHgrow(rightBottom, Priority.ALWAYS);

        statsPane.setMinHeight(70);
        VBox.setVgrow(statsPane, Priority.NEVER);

        // === HUD Tempo/Meteo compatto ===
        cycleImage = new ImageView();
        cycleImage.setPreserveRatio(true);
        cycleImage.setSmooth(true);
        cycleImage.setCache(true);
        cycleImage.setFitHeight(32); // 🔹 più piccolo e compatto
        cycleImage.setImage(safeLoad("/images/noon.png", "/images/noon.png"));

        weatherIcon = new ImageView();
        weatherIcon.setFitWidth(24);
        weatherIcon.setFitHeight(24);

        timeLabel = new Label("...");
        timeLabel.setTextFill(Color.web("#e0e0c0"));
        timeLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        // <<< AGGIUNTO: badge IMMORTALE (nascosto finché off)
        statusImmortalLabel = new Label("❄ IMMORTALE");
        statusImmortalLabel.setVisible(false);
        statusImmortalLabel.getStyleClass().add("immortal-badge"); // per lo stile figo
        HBox timeHud = new HBox(8, timeLabel, weatherIcon, cycleImage, statusImmortalLabel);
        timeHud.setAlignment(Pos.CENTER_RIGHT); // tutto allineato a destra
        timeHud.setPadding(new Insets(2, 8, 2, 0)); // margine a destra

        // === Minimap (dual-mode) ===
        minimapGrid = new GridPane();
        minimapGrid.setHgap(0);
        minimapGrid.setVgap(0);
        minimapGrid.setPadding(new Insets(4));
        minimapGrid.setAlignment(Pos.CENTER);
        minimapGrid.setStyle("-fx-background-color:#0e0e0e; -fx-border-color:#303030; -fx-border-width:1;");

        minimapPane = new Pane();
        minimapPane.setPickOnBounds(false);
        minimapPane.setStyle("-fx-background-color:#0e0e0e; -fx-border-color:#303030; -fx-border-width:1;");

        // contenitore unico che tiene dentro entrambi
        StackPane minimapContainer = new StackPane(minimapGrid, minimapPane);
        minimapContainer.setAlignment(Pos.CENTER);
        minimapContainer.setPadding(new Insets(4));

        // default → solo overworld (ortho)
        minimapGrid.setVisible(true);
        minimapPane.setVisible(false);

        // === Croce direzionale ===
        Button btnN = makeNavButton("▲");
        Button btnS = makeNavButton("▼");
        Button btnE = makeNavButton("▶");
        Button btnO = makeNavButton("◀");
        Button btnLook = makeNavButton("");

        // 🔹 Pulsanti alto/basso leggermente più piccoli ma centrati e visibili
        Button btnUpLevel = new Button();
        btnUpLevel.setPrefSize(36, 36);
        btnUpLevel.setMinSize(36, 36);
        btnUpLevel.setMaxSize(36, 36);

        // grafica del pulsante "⬆"
        Text upIcon = new Text("⬆");
        upIcon.setStyle(
                "-fx-font-family:'Segoe UI Emoji','Segoe UI Symbol','Arial Unicode MS';" +
                        "-fx-font-size:18px;" +
                        "-fx-fill:#e0e0c0;");
        btnUpLevel.setGraphic(upIcon);
        btnUpLevel.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        btnUpLevel.setAlignment(Pos.CENTER);
        btnUpLevel.setStyle(
                "-fx-background-color:#333;" +
                        "-fx-border-color:#caa85a;" +
                        "-fx-border-width:2;" +
                        "-fx-border-radius:8;" +
                        "-fx-background-radius:8;");

        // === Pulsante “⬇” ===
        Button btnDownLevel = new Button();
        btnDownLevel.setPrefSize(36, 36);
        btnDownLevel.setMinSize(36, 36);
        btnDownLevel.setMaxSize(36, 36);

        // grafica del pulsante "⬇"
        Text downIcon = new Text("⬇");
        downIcon.setStyle(
                "-fx-font-family:'Segoe UI Emoji','Segoe UI Symbol','Arial Unicode MS';" +
                        "-fx-font-size:18px;" +
                        "-fx-fill:#e0e0c0;");
        btnDownLevel.setGraphic(downIcon);
        btnDownLevel.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        btnDownLevel.setAlignment(Pos.CENTER);
        btnDownLevel.setStyle(
                "-fx-background-color:#333;" +
                        "-fx-border-color:#caa85a;" +
                        "-fx-border-width:2;" +
                        "-fx-border-radius:8;" +
                        "-fx-background-radius:8;");
        // fine blocco pulsanti

        // === Stile uniforme ===
        String navStyle = "-fx-background-color:#333;" +
                "-fx-text-fill:#e0e0c0;" +
                "-fx-font-size:18px;" +
                "-fx-background-radius:8;" +
                "-fx-border-color:#caa85a;" +
                "-fx-border-width:2;" +
                "-fx-border-radius:8;";

        btnN.setStyle(navStyle);
        btnS.setStyle(navStyle);
        btnE.setStyle(navStyle);
        btnO.setStyle(navStyle);
        btnUpLevel.setStyle(navStyle);
        btnDownLevel.setStyle(navStyle);

        btnLook.setPrefSize(NAV_BTN_CENTER, NAV_BTN_CENTER);

        // 👁 Icona occhio ben visibile e centrata
        Text eye = new Text("👁");
        eye.setStyle(
                "-fx-font-family:'Segoe UI Emoji','Segoe UI Symbol';" +
                        "-fx-font-size:22px;" + // leggermente più piccolo per centratura perfetta
                        "-fx-fill:#e0e0c0;" // colore chiaro visibile sul fondo scuro
        );

        // Applica l’icona al bottone
        btnLook.setGraphic(eye);
        btnLook.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

        // Stile coerente con gli altri bottoni, ma con testo chiaro
        btnLook.setStyle(
                "-fx-background-color:#333;" +
                        "-fx-background-radius:8;" +
                        "-fx-border-color:#caa85a;" +
                        "-fx-border-width:2;" +
                        "-fx-border-radius:8;" +
                        "-fx-text-fill:#e0e0c0;" // colore di fallback del testo
        );

        // Disabilita parsing di scorciatoie da tastiera
        btnLook.setMnemonicParsing(false);

        // === Azioni dei pulsanti ===
        btnN.setOnAction(e -> sendCmd("vai nord"));
        btnS.setOnAction(e -> sendCmd("vai sud"));
        btnE.setOnAction(e -> sendCmd("vai est"));
        btnO.setOnAction(e -> sendCmd("vai ovest"));
        btnLook.setOnAction(e -> sendCmd("guarda"));
        btnUpLevel.setOnAction(e -> sendCmd("alto"));
        btnDownLevel.setOnAction(e -> sendCmd("basso"));

        // === Griglia principale (3x3) ===
        GridPane navGrid = new GridPane();
        navGrid.setHgap(4);
        navGrid.setVgap(4);
        navGrid.setAlignment(Pos.CENTER);

        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(33.333);
            cc.setHalignment(HPos.CENTER);
            navGrid.getColumnConstraints().add(cc);

            RowConstraints rc = new RowConstraints();
            rc.setPercentHeight(33.333);
            rc.setValignment(VPos.CENTER);
            navGrid.getRowConstraints().add(rc);
        }

        // === Posizionamento pulsanti standard ===
        navGrid.add(btnN, 1, 0);
        navGrid.add(btnO, 0, 1);
        navGrid.add(btnLook, 1, 1);
        navGrid.add(btnE, 2, 1);
        navGrid.add(btnS, 1, 2);

        // === Pulsante Inventario ===
        Button btnInventory = new Button("I");
        btnInventory.setPrefSize(36, 36);
        btnInventory.setStyle(
                "-fx-background-color:#333;" +
                        "-fx-text-fill:#e0e0c0;" +
                        "-fx-font-size:16px;" +
                        "-fx-font-weight:bold;" +
                        "-fx-border-color:#caa85a;" +
                        "-fx-border-width:2;" +
                        "-fx-background-radius:50%;" +
                        "-fx-border-radius:50%;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 4, 0.2, 0, 1);");
        btnInventory.setOnAction(e -> sendCmd("inv"));

        // === Pulsante Equipaggiamento ===
        Button btnEquip = new Button("E");
        btnEquip.setPrefSize(36, 36);
        btnEquip.setStyle(
                "-fx-background-color:#333;" +
                        "-fx-text-fill:#e0e0c0;" +
                        "-fx-font-size:16px;" +
                        "-fx-font-weight:bold;" +
                        "-fx-border-color:#caa85a;" +
                        "-fx-border-width:2;" +
                        "-fx-background-radius:50%;" +
                        "-fx-border-radius:50%;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 4, 0.2, 0, 1);");
        btnEquip.setOnAction(e -> sendCmd("equip"));

        // === Colonna laterale aggiornata ===
        VBox sideBox = new VBox(8, btnUpLevel, btnDownLevel, btnInventory, btnEquip);
        sideBox.setAlignment(Pos.CENTER);

        // === Effetti hover & click per tutti i pulsanti direzionali e laterali ===

        // Stile base riutilizzabile (quadrato)
        String mainBtnBaseStyle = "-fx-background-color:#333;" +
                "-fx-text-fill:#e0e0c0;" +
                "-fx-font-size:18px;" +
                "-fx-font-weight:bold;" +
                "-fx-border-color:#caa85a;" +
                "-fx-border-width:2;" +
                "-fx-background-radius:8;" +
                "-fx-border-radius:8;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 4, 0.2, 0, 1);";

        // Stile base per pulsanti tondi (⬆ ⬇ I E)
        String sideBtnBaseStyle = "-fx-background-color:#333;" +
                "-fx-text-fill:#e0e0c0;" +
                "-fx-font-size:16px;" +
                "-fx-font-weight:bold;" +
                "-fx-border-color:#caa85a;" +
                "-fx-border-width:2;" +
                "-fx-background-radius:50%;" +
                "-fx-border-radius:50%;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 4, 0.2, 0, 1);";

        // Funzione per applicare hover + click animato
        java.util.function.BiConsumer<Button, Boolean> applyInteractiveEffects = (Button b, Boolean isRound) -> {
            String baseStyle = isRound ? sideBtnBaseStyle : mainBtnBaseStyle;

            // Hover: glow dorato
            b.setOnMouseEntered(e -> b.setStyle(
                    baseStyle +
                            "-fx-background-color:#4a3a15;" + // dorato scuro
                            "-fx-effect: dropshadow(gaussian, rgba(255,215,0,0.6), 8, 0.3, 0, 0);"));

            // Uscita hover: torna normale
            b.setOnMouseExited(e -> b.setStyle(baseStyle));

            // Click: scurimento temporaneo
            b.setOnMousePressed(e -> {
                b.setStyle(
                        baseStyle +
                                "-fx-background-color:#222;" +
                                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 6, 0.3, 0, 0);");
                javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(150));
                t.setOnFinished(ev -> b.setStyle(baseStyle));
                t.play();
            });
        };

        // === Applica effetti ai pulsanti direzionali principali ===
        applyInteractiveEffects.accept(btnN, false);
        applyInteractiveEffects.accept(btnS, false);
        applyInteractiveEffects.accept(btnE, false);
        applyInteractiveEffects.accept(btnO, false);
        applyInteractiveEffects.accept(btnLook, false);

        // === Applica effetti ai pulsanti laterali (tondi) ===
        applyInteractiveEffects.accept(btnUpLevel, true);
        applyInteractiveEffects.accept(btnDownLevel, true);
        applyInteractiveEffects.accept(btnInventory, true);
        applyInteractiveEffects.accept(btnEquip, true);

        // === Composizione finale (croce + lato destro) ===
        HBox navWrapperBox = new HBox(8, navGrid, sideBox);
        navWrapperBox.setAlignment(Pos.CENTER);

        // === Output area ===
        outputArea = new StyleClassedTextArea();
        outputArea.setParagraphGraphicFactory(idx -> paragraphIconMap.get(idx));
        outputArea.setEditable(false);
        outputArea.setWrapText(true);
        outputArea.setStyle(
                "-fx-background-color:#101010;" +
                        "-fx-font-family:'Consolas';" +
                        "-fx-font-size:14px;");

        // 🔹 crea lo scroll pane come campo di classe
        outputScroll = new VirtualizedScrollPane<>(outputArea);
        outputWrapper = new StackPane(outputScroll);
        outputWrapper.setPadding(new Insets(8));
        outputWrapper.setStyle("-fx-background-color: transparent;");

        // 🔁 Ripristina lo storico re-renderizzando con gli stili
        if (logBuf.length() > 0) {
            outputArea.clear();
            paragraphIconMap.clear();
            renderingFromBuffer = true; // ⬅️ non reinserire nel buffer
            appendWithAnsiColors(logBuf.toString()); // ⬅️ ricalcola colori/stili
            renderingFromBuffer = false;
        }

        uiInitialized = true;

        // === Input + Exit ===
        inputField = new TextField();
        inputField.setPromptText("Scrivi un comando...");
        inputField.setStyle("-fx-background-color:#202020; -fx-text-fill:#e0e0c0; -fx-font-family:'Consolas';");
        // === Gestione cronologia comandi ===
        java.util.List<String> commandHistory = new java.util.ArrayList<>();
        final int[] historyIndex = { -1 };

        inputField.setOnKeyPressed(k -> {
            if (k.getCode() == KeyCode.ENTER) {
                String txt = inputField.getText().trim();
                if (!txt.isEmpty()) {
                    commandHistory.add(txt);
                    historyIndex[0] = commandHistory.size();
                    sendCommand();
                }
                Platform.runLater(() -> inputField.requestFocus());
            } else if (k.getCode() == KeyCode.UP) {
                if (!commandHistory.isEmpty() && historyIndex[0] > 0) {
                    historyIndex[0]--;
                    inputField.setText(commandHistory.get(historyIndex[0]));
                    inputField.positionCaret(inputField.getText().length());
                }
            } else if (k.getCode() == KeyCode.DOWN) {
                if (!commandHistory.isEmpty() && historyIndex[0] < commandHistory.size() - 1) {
                    historyIndex[0]++;
                    inputField.setText(commandHistory.get(historyIndex[0]));
                    inputField.positionCaret(inputField.getText().length());
                } else {
                    inputField.clear();
                    historyIndex[0] = commandHistory.size();
                }
            }
        });

        // === Pulsanti Disconnetti / Exit con effetto dorato ===
        String actionBtnStyle = "-fx-background-color:#333;" +
                "-fx-text-fill:#e0e0c0;" +
                "-fx-font-size:14px;" +
                "-fx-font-weight:bold;" +
                "-fx-border-color:#caa85a;" +
                "-fx-border-width:2;" +
                "-fx-background-radius:8;" +
                "-fx-border-radius:8;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 4, 0.2, 0, 1);";

        // Funzione per hover + click animato
        java.util.function.Consumer<Button> applyActionButtonEffects = (Button b) -> {
            // hover → glow dorato
            b.setOnMouseEntered(e -> b.setStyle(
                    actionBtnStyle +
                            "-fx-background-color:#4a3a15;" +
                            "-fx-effect: dropshadow(gaussian, rgba(255,215,0,0.6), 8, 0.3, 0, 0);"));
            // ritorno normale
            b.setOnMouseExited(e -> b.setStyle(actionBtnStyle));
            // click → breve scurimento
            b.setOnMousePressed(e -> {
                b.setStyle(
                        actionBtnStyle +
                                "-fx-background-color:#222;" +
                                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 6, 0.3, 0, 0);");
                javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(150));
                t.setOnFinished(ev -> b.setStyle(actionBtnStyle));
                t.play();
            });
        };

        // === Pulsante Disconnetti ===
        Button disconnectBtn = new Button("⚡ Disconnetti");
        disconnectBtn.setStyle(actionBtnStyle);
        applyActionButtonEffects.accept(disconnectBtn);
        disconnectBtn.setOnAction(e -> {
            Stage dialog = new Stage();
            dialog.setTitle("Disconnessione");

            Label lbl = new Label("Disconnessione in 5 secondi...");
            lbl.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 16px;");

            final Timeline[] timelineRef = new Timeline[1];

            Button cancelBtn = new Button("Annulla");
            cancelBtn.setOnAction(ev -> {
                if (timelineRef[0] != null)
                    timelineRef[0].stop();
                dialog.close();
            });

            VBox box = new VBox(15, lbl, cancelBtn);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(20));
            box.setStyle("-fx-background-color: #1b1b1b;");

            Scene sc = new Scene(box, 320, 150);
            dialog.setScene(sc);
            dialog.initOwner(primary);
            dialog.setResizable(false);
            dialog.show();

            final int[] counter = { 5 };
            Timeline timeline = new Timeline(
                    new KeyFrame(Duration.seconds(1), ev -> {
                        counter[0]--;
                        if (counter[0] > 0) {
                            lbl.setText("Disconnessione in " + counter[0] + " secondi...");
                        } else {
                            dialog.close();
                            try {
                                closing = true;
                                if (out != null) {
                                    JsonObject cmd = new JsonObject();
                                    cmd.addProperty("type", "cmd");
                                    cmd.addProperty("text", "exit");
                                    out.println(gson.toJson(cmd));
                                }
                                if (socket != null && !socket.isClosed())
                                    socket.close();
                            } catch (IOException ignored) {
                            }
                            socket = null;
                            in = null;
                            out = null;
                            Platform.runLater(this::showLogin);
                        }
                    }));
            timeline.setCycleCount(5);
            timeline.play();
            timelineRef[0] = timeline;
        });

        // === Pulsante Exit ===
        Button exitBtn = new Button("❌ Exit");
        exitBtn.setStyle(actionBtnStyle);
        applyActionButtonEffects.accept(exitBtn);
        exitBtn.setOnAction(e -> {
            Stage dialog = new Stage();
            dialog.setTitle("Uscita dal gioco");

            Label lbl = new Label("Disconnessione in 5 secondi...");
            lbl.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 16px;");

            // ✅ Timeline referenziabile anche da Annulla
            final Timeline[] timelineRef = new Timeline[1];

            Button cancelBtn = new Button("Annulla");
            cancelBtn.setOnAction(ev -> {
                if (timelineRef[0] != null) {
                    timelineRef[0].stop(); // ✅ ferma il countdown
                }
                dialog.close();
                System.out.println("[ClientApp] Countdown annullato: uscita interrotta.");
            });

            VBox box = new VBox(15, lbl, cancelBtn);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(20));
            box.setStyle("-fx-background-color: #1b1b1b;");

            Scene sc = new Scene(box, 320, 150);
            dialog.setScene(sc);
            dialog.initOwner(primary);
            dialog.setResizable(false);
            dialog.show();

            final int[] counter = { 5 };
            Timeline timeline = new Timeline(
                    new KeyFrame(Duration.seconds(1), ev -> {
                        counter[0]--;
                        if (counter[0] > 0) {
                            lbl.setText("Disconnessione in " + counter[0] + " secondi...");
                        } else {
                            dialog.close();
                            closing = true;
                            try {
                                if (out != null) {
                                    JsonObject cmd = new JsonObject();
                                    cmd.addProperty("type", "cmd");
                                    cmd.addProperty("text", "exit");
                                    out.println(gson.toJson(cmd));
                                }
                                if (socket != null && !socket.isClosed())
                                    socket.close();
                            } catch (IOException ignored) {
                            }
                            Platform.runLater(() -> primary.close());
                        }
                    }));

            timeline.setCycleCount(5);
            timeline.play();
            timelineRef[0] = timeline; // ✅ salva riferimento per annullamento
        });

        HBox commandLine = new HBox(6, inputField, disconnectBtn, exitBtn);
        commandLine.setPadding(new Insets(6));
        HBox.setHgrow(inputField, Priority.ALWAYS);

        // === Layout principale ===
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(8));
        root.setStyle("-fx-background-color:#1b1b1b;");

        VBox topBox = new VBox(6, statsPane, timeHud);
        root.setTop(topBox);

        root.setCenter(outputWrapper);
        BorderPane.setMargin(outputWrapper, new Insets(6, 6, 6, 6));
        VBox.setVgrow(outputWrapper, Priority.ALWAYS);

        root.setBottom(commandLine);

        StackPane navWrapper = new StackPane(navWrapperBox);
        navWrapper.setAlignment(Pos.CENTER);
        navWrapper.prefWidthProperty().bind(minimapGrid.widthProperty());
        navWrapper.setPadding(new Insets(8));
        navWrapper.setStyle(
                "-fx-background-color:#1a1a1a;" +
                        "-fx-border-color:#caa85a;" +
                        "-fx-border-width:2;" +
                        "-fx-border-radius:8;" +
                        "-fx-background-radius:8;");

        VBox rightBox = new VBox(12, navWrapper, minimapContainer);
        rightBox.setAlignment(Pos.BOTTOM_RIGHT);
        rightBox.setPadding(new Insets(6));
        root.setRight(rightBox);

        Scene scene = new Scene(root, 1200, 800);
        var css = getClass().getResource("/dark.css");
        if (css != null)
            scene.getStylesheets().add(css.toExternalForm());
        var css2 = getClass().getResource("/richtext.css");
        if (css2 != null)
            scene.getStylesheets().add(css2.toExternalForm());
        var css3 = getClass().getResource("/app.css");
        if (css3 != null)
            scene.getStylesheets().add(css3.toExternalForm());

        primary.setTitle("⚔ New Client 2.4 ⚔");
        primary.setScene(scene);
        primary.show();
        primary.centerOnScreen();

        Platform.runLater(() -> inputField.requestFocus());
    }

    // =============================================================================================
    // HUD helpers
    // =============================================================================================
    private Label makeStatLabel(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web("#e0e0c0"));
        return l;
    }

    private Label makePctLabel() {
        Label pct = new Label("100%");
        pct.setStyle("-fx-text-fill: black; -fx-font-weight: bold;"); // solo testo nero in grassetto
        pct.setMouseTransparent(true);
        return pct;
    }

    private HBox makeHudRow(Label label, ProgressBar bar, Label pct) {
        // StackPane con la barra sotto e la percentuale sopra
        StackPane barStack = new StackPane(bar);
        StackPane.setAlignment(pct, Pos.CENTER); // testo centrato
        barStack.getChildren().add(pct);

        HBox row = new HBox(10, label, barStack);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(barStack, Priority.ALWAYS);
        bar.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private ProgressBar makeBar(String hexColor) {
        ProgressBar bar = new ProgressBar(1.0);
        bar.setPrefHeight(24); // 👈 aumentato (era 22)
        bar.setStyle(
                "-fx-accent: " + hexColor + ";" +
                        "-fx-background-insets: 0, 1, 2;" +
                        "-fx-background-radius: 14px;" + // 👈 più arrotondato
                        "-fx-border-radius: 14px;" + // 👈 più arrotondato
                        "-fx-effect: innershadow(gaussian, rgba(0,0,0,0.65), 8, 0.3, 0, 1);");
        return bar;
    }

    // Campo già presente o aggiungilo tra i fields della classe
    private boolean immortalMode = false;
    private Label statusImmortalLabel; // <<< AGGIUNTO

    // === Immortal UI ===
    private void applyFrozenUI(boolean on) {
        this.immortalMode = on;

        // Applica/rimuove la classe CSS "frozen" alle barre
        toggleFrozenStyle(healthBar, on);
        toggleFrozenStyle(staminaBar, on);
        toggleFrozenStyle(hungerBar, on);
        toggleFrozenStyle(thirstBar, on);

        // Badge opzionale
        if (statusImmortalLabel != null) {
            statusImmortalLabel.setVisible(on);
            statusImmortalLabel.setText(on ? "❄ IMMORTALE" : "");
        }

        if (on) {
            javafx.scene.effect.DropShadow glow = new javafx.scene.effect.DropShadow();
            glow.setColor(javafx.scene.paint.Color.web("rgba(120,190,255,0.75)"));
            glow.setRadius(18);
            glow.setSpread(0.45);

            java.util.List<ProgressBar> bars = java.util.List.of(healthBar, staminaBar, hungerBar, thirstBar);
            bars.forEach(b -> b.setEffect(glow));

            javafx.animation.Timeline pulse = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(0),
                            new javafx.animation.KeyValue(glow.radiusProperty(), 16)),
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(320),
                            new javafx.animation.KeyValue(glow.radiusProperty(), 24)),
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(640),
                            new javafx.animation.KeyValue(glow.radiusProperty(), 16)));
            pulse.setCycleCount(3);
            pulse.setOnFinished(e -> bars.forEach(b -> b.setEffect(null)));
            pulse.play();
        }
    }

    private void toggleFrozenStyle(javafx.scene.control.ProgressBar bar, boolean on) {
        if (bar == null)
            return;
        var css = bar.getStyleClass();
        if (on) {
            if (!css.contains("frozen"))
                css.add("frozen");
            if (bar.getTooltip() == null) {
                bar.setTooltip(new Tooltip("Congelato: modalità Immortale"));
            }
        } else {
            css.remove("frozen");
            bar.setTooltip(null);
        }
    }

    void updateStatus(int health, int stamina, int hunger, int thirst) {
        healthBar.setProgress(Math.max(0, Math.min(1.0, health / 100.0)));
        staminaBar.setProgress(Math.max(0, Math.min(1.0, stamina / 100.0)));
        hungerBar.setProgress(Math.max(0, Math.min(1.0, hunger / 100.0)));
        thirstBar.setProgress(Math.max(0, Math.min(1.0, thirst / 100.0)));

        healthPct.setText(health + "%");
        staminaPct.setText(stamina + "%");
        hungerPct.setText(hunger + "%");
        thirstPct.setText(thirst + "%");

        // Applica/rimuove effetto "ghiaccio" in base a immortalMode
        toggleFrozenStyle(healthBar, immortalMode);
        toggleFrozenStyle(staminaBar, immortalMode);
        toggleFrozenStyle(hungerBar, immortalMode);
        toggleFrozenStyle(thirstBar, immortalMode);
    }

    // =============================================================================================
    // rete
    // =============================================================================================
    private void sendCommand() {
        String txt = inputField.getText().trim();
        if (txt.isEmpty())
            return;

        JsonObject cmd = new JsonObject();
        cmd.addProperty("type", "cmd");
        cmd.addProperty("text", txt);

        out.println(gson.toJson(cmd));
        inputField.clear();
    }

    private void connectToServer(String host, int port) throws IOException {
        if (socket != null && socket.isConnected())
            return;

        socket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
    }

    private void startReaderLoop() {
        // ✅ Evita doppi listener attivi
        if (!readerRunning.compareAndSet(false, true)) {
            System.out.println("[ReaderLoop] Già in esecuzione, skip.");
            return;
        }

        if (socket == null || socket.isClosed() || in == null) {
            System.out.println("[ReaderLoop] Socket non valida, non avvio lettura.");
            readerRunning.set(false);
            return;
        }

        Executors.newSingleThreadExecutor().submit(() -> {
            System.out.println("[ReaderLoop] Avviato listener del server.");
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonObject r = gson.fromJson(line, JsonObject.class);
                    if (r != null) {
                        JsonObject finalR = r;
                        Platform.runLater(() -> handleServerMessage(finalR));
                    }
                }
                System.out.println("[ReaderLoop] Stream chiuso dal server.");
            } catch (IOException e) {
                System.out.println("[ReaderLoop] Connessione interrotta: " + e.getMessage());
                Platform.runLater(() -> {
                    appendWithAnsiColors("§cConnessione interrotta.§r\n");
                    appendWithAnsiColors("§eAttendo che il server torni online...§r\n");
                });
            } finally {
                // ✅ Impedisci doppi reader
                readerRunning.set(false);

                // ✅ Chiudi solo una volta
                closeSocketSafely();

                // 🚫 Se l'utente ha chiesto la disconnessione o l'app sta chiudendo, non
                // tentare riconnessione
                if (closing) {
                    System.out.println("[ReaderLoop] Chiusura intenzionale: skip riconnessione automatica.");
                    closing = false; // resetta flag per eventuale login successivo
                    return;
                }

                // ✅ Esegui tentativo di riconnessione solo se non è una chiusura manuale
                boolean reconn = tryReconnect();

                if (reconn) {
                    Platform.runLater(() -> {
                        // separatore visivo di nuova sessione
                        appendWithAnsiColors("§7───────── §e[Riconnessione]§7 ─────────§r\n");
                        appendWithAnsiColors("§a✅ Riavvio completato. Sei di nuovo connesso.§r\n");
                    });
                } else {
                    Platform.runLater(() -> {
                        appendWithAnsiColors("§c❌ Impossibile riconnettersi. Torno al login.§r\n");
                        showLogin();
                    });
                }
            }

        });
    }

    private void closeSocketSafely() {
        try {
            if (socket != null && !socket.isClosed())
                socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    // 🔹 helper per inviare un comando al server
    private void sendCmd(String text) {
        if (socket == null || out == null || socket.isClosed()) {
            appendWithAnsiColors("§c[errore] Connessione non attiva: impossibile inviare comandi.§r\n");
            return;
        }

        try {
            JsonObject cmd = new JsonObject();
            cmd.addProperty("type", "cmd");
            cmd.addProperty("text", text);
            out.println(gson.toJson(cmd));
        } catch (Exception ex) {
            appendWithAnsiColors("§c[errore] Invio comando fallito: " + ex.getMessage() + "§r\n");
        }
    }

    // =============================================================================================
    // parsing messaggi server
    // =============================================================================================
    private void handleServerMessage(JsonObject r) {
        if (r == null)
            return;

        // 👇 intercetta e aggiorna la velocità se il messaggio la contiene
        maybeUpdateMovementSpeed(r);

        String type = r.has("type") ? r.get("type").getAsString() : "";

        switch (type) {
            case "info":
            case "system": {
                String msg = r.has("message") ? r.get("message").getAsString() : "";

                if (msg.contains("Il server verrà riavviato")) {
                    appendWithAnsiColors(msg + "\n");
                    appendGlowEffect("gold"); // 💫 effetto luce dorata
                    return;
                }
                if (msg.contains("Resterete connessi automaticamente")) {
                    appendWithAnsiColors(msg + "\n");
                    appendGlowEffect("cyan"); // 💫 effetto luce ciano
                    return;
                }

                if (msg.startsWith("[broadcast]")) {
                    msg = msg.replaceFirst("\\[broadcast\\]", "").trim();
                    appendWithAnsiColors("§e✨ " + msg + " ✨§r\n"); // colore oro brillante
                    appendGlowEffect("gold"); // glow dorato temporaneo
                    return;
                }

                // 🔔 Broadcast in formato banner "ANNUNCIO GLOBALE"
                if (msg.toLowerCase().contains("annuncio globale")) {
                    appendWithAnsiColors(msg + "\n"); // mantieni il banner su più righe
                    appendGlowEffect("gold"); // glow dorato temporaneo
                    return;
                }

                appendWithIcons(msg); // default
                break;
            }

            case "msg": {
                String msg = r.has("message") ? r.get("message").getAsString() : "";

                // 🔹 Filtra eventuali prefissi [errore]
                if (msg.startsWith("[errore]")) {
                    msg = msg.replaceFirst("(?i)^\\[errore\\]\\s*", "");
                }

                appendWithIcons(msg);
                break;
            }

            case "time": {
                String display = r.has("display") ? r.get("display").getAsString() : "";
                double dayProgress = r.has("dayProgress") ? r.get("dayProgress").getAsDouble() : 0.0;
                String sun = r.has("sunPhase") ? r.get("sunPhase").getAsString() : "notte";
                String moon = r.has("moonPhase") ? r.get("moonPhase").getAsString() : "novilunio";
                String weather = r.has("weather") ? r.get("weather").getAsString() : "sereno";

                Platform.runLater(() -> {
                    timeLabel.setText(display);
                    cycleImage.setImage(imageForCycle(sun, moon));
                    weatherIcon.setImage(imageForWeather(weather));
                });

                break;
            }

            case "":
                if (r.has("text")) {
                    appendWithAnsiColors(r.get("text").getAsString());
                } else {
                    appendWithAnsiColors("[raw] " + r.toString());
                }
                break;

            case "minimap": {
                int tileSize = r.has("tileSize") ? r.get("tileSize").getAsInt() : TILE_SIZE;
                int view = r.has("view") ? r.get("view").getAsInt() : VIEW_SIZE;

                // forza 7x7 nelle città/dungeon
                if (isCityLike()) {
                    view = 7;
                    tileSize = TILE_SIZE; // 19 px
                }
                int cx = r.has("cx") ? r.get("cx").getAsInt() : -1;
                int cy = r.has("cy") ? r.get("cy").getAsInt() : -1;
                String icon = r.has("icon") ? r.get("icon").getAsString() : "default.gif";
                String map = r.has("map") ? r.get("map").getAsString() : "overworld";
                currentMapName = map;
                boolean isoFlagFromServer = r.has("iso") && r.get("iso").getAsBoolean();
                boolean iso = r.has("iso") ? isoFlagFromServer : isIsoMap(currentMapName);
                setMinimapMode(iso);

                // NEW: stato piani/indoor dal server
                if (r.has("playerFloor")) {
                    this.playerFloor = r.get("playerFloor").getAsInt();
                }
                if (r.has("outdoor")) {
                    this.isOutdoor = r.get("outdoor").getAsBoolean();
                }

                if (r.has("data") && r.get("data").isJsonArray()) {
                    JsonArray rows = r.getAsJsonArray("data");
                    currentViewSize = view;
                    if (isoMode) {
                        updateMiniMapIso(r, rows, tileSize, view, cx, cy, icon); // 👈 passiamo anche r (JsonObject
                                                                                 // payload)
                    } else {
                        updateMiniMap(rows, tileSize, view, cx, cy, icon);
                    }
                }

                if (r.has("others") && r.get("others").isJsonArray()) {
                    if (isoMode) {
                        updateMiniMapWithPlayers(r.getAsJsonArray("others"));
                    } else {
                        overlayOthersOnMiniMap(r.getAsJsonArray("others"), cx, cy, currentViewSize);
                    }
                }

                // === NEW: overlay mob/animali sulla minimappa (icone proporzionate) ===
                if (r.has("mobs") && r.get("mobs").isJsonArray()) {
                    JsonArray mobs = r.getAsJsonArray("mobs");

                    double mobScale = TILE_SIZE * 0.7 * MINIMAP_ZOOM; // 👈 70% della tile (fit naturale)

                    if (isoMode) {
                        // --- città/dungeon (iso) ---
                        for (JsonElement e : mobs) {
                            JsonObject mo = e.getAsJsonObject();
                            int dx = mo.has("dx") ? mo.get("dx").getAsInt() : 0;
                            int dy = mo.has("dy") ? mo.get("dy").getAsInt() : 0;
                            String mobIcon = mo.has("icon") ? mo.get("icon").getAsString() : "mob_default.gif";

                            int relX = currentViewSize / 2 + dx;
                            int relY = currentViewSize / 2 + dy;
                            StackPane cell = isoCells.get(relX + "," + relY);
                            if (cell != null) {
                                try (InputStream in = getClass().getResourceAsStream("/maps/icons/" + mobIcon)) {
                                    InputStream src = in != null ? in
                                            : getClass().getResourceAsStream("/maps/icons/mob_default.gif");
                                    Image img = new Image(src, mobScale, mobScale, true, true);
                                    ImageView iv = new ImageView(img);
                                    iv.setPreserveRatio(true);
                                    iv.setSmooth(true);
                                    iv.setFitWidth(mobScale);
                                    iv.setFitHeight(mobScale);
                                    iv.setTranslateY(-2); // piccolo offset per centratura visiva
                                    cell.getChildren().add(iv);
                                } catch (Exception ex) {
                                    System.err.println(
                                            "[MINIMAP] Errore caricando icona mob " + mobIcon + ": " + ex.getMessage());
                                }
                            }
                        }
                    } else {
                        // --- overworld (griglia ortogonale) ---
                        for (JsonElement e : mobs) {
                            JsonObject mo = e.getAsJsonObject();
                            int dx = mo.has("dx") ? mo.get("dx").getAsInt() : 0;
                            int dy = mo.has("dy") ? mo.get("dy").getAsInt() : 0;
                            String mobIcon = mo.has("icon") ? mo.get("icon").getAsString() : "mob_default.gif";

                            int relX = currentViewSize / 2 + dx;
                            int relY = currentViewSize / 2 + dy;

                            for (javafx.scene.Node node : minimapGrid.getChildren()) {
                                Integer col = GridPane.getColumnIndex(node);
                                Integer row = GridPane.getRowIndex(node);
                                if (col != null && row != null && col == relX && row == relY
                                        && node instanceof StackPane sp) {
                                    try (InputStream in = getClass().getResourceAsStream("/maps/icons/" + mobIcon)) {
                                        InputStream src = in != null ? in
                                                : getClass().getResourceAsStream("/maps/icons/mob_default.gif");
                                        Image img = new Image(src, mobScale, mobScale, true, true);
                                        ImageView iv = new ImageView(img);
                                        iv.setPreserveRatio(true);
                                        iv.setSmooth(true);
                                        iv.setFitWidth(mobScale);
                                        iv.setFitHeight(mobScale);
                                        iv.setTranslateY(-1);
                                        sp.getChildren().add(iv);
                                    } catch (Exception ex) {
                                        System.err.println("[MINIMAP] Errore caricando icona mob " + mobIcon + ": "
                                                + ex.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }

                // === NEW: overlay affioramenti / depositi sulla minimappa ===
                if (r.has("deposits") && r.get("deposits").isJsonArray()) {
                    JsonArray deposits = r.getAsJsonArray("deposits");

                    double depScale = TILE_SIZE * 0.7 * MINIMAP_ZOOM; // leggermente più piccolo

                    if (isoMode) {
                        // modalità città/dungeon (isometrica)
                        for (JsonElement e : deposits) {
                            JsonObject d = e.getAsJsonObject();
                            int dx = d.has("dx") ? d.get("dx").getAsInt() : 0;
                            int dy = d.has("dy") ? d.get("dy").getAsInt() : 0;
                            String depositIcon = d.has("icon") ? d.get("icon").getAsString() : null;

                            if (depositIcon == null || depositIcon.isBlank())
                                continue; // niente icona = niente disegno

                            int relX = currentViewSize / 2 + dx;
                            int relY = currentViewSize / 2 + dy;
                            StackPane cell = isoCells.get(relX + "," + relY);
                            if (cell != null) {
                                try (InputStream in = getClass().getResourceAsStream("/maps/icons/" + depositIcon)) {
                                    if (in == null)
                                        continue; // file non trovato → salta
                                    Image img = new Image(in, depScale, depScale, true, true);
                                    ImageView iv = new ImageView(img);
                                    iv.setPreserveRatio(true);
                                    iv.setSmooth(true);
                                    iv.setFitWidth(depScale);
                                    iv.setFitHeight(depScale);
                                    iv.setTranslateY(-1);
                                    cell.getChildren().add(iv);
                                } catch (Exception ex) {
                                    System.err.println("[MINIMAP] Errore caricando icona affioramento " + depositIcon
                                            + ": " + ex.getMessage());
                                }
                            }
                        }
                    } else {
                        // modalità overworld (griglia ortogonale)
                        for (JsonElement e : deposits) {
                            JsonObject d = e.getAsJsonObject();
                            int dx = d.has("dx") ? d.get("dx").getAsInt() : 0;
                            int dy = d.has("dy") ? d.get("dy").getAsInt() : 0;
                            String depositIcon = d.has("icon") ? d.get("icon").getAsString() : null;

                            if (depositIcon == null || depositIcon.isBlank())
                                continue;

                            int relX = currentViewSize / 2 + dx;
                            int relY = currentViewSize / 2 + dy;

                            for (javafx.scene.Node node : minimapGrid.getChildren()) {
                                Integer col = GridPane.getColumnIndex(node);
                                Integer row = GridPane.getRowIndex(node);
                                if (col != null && row != null && col == relX && row == relY
                                        && node instanceof StackPane sp) {
                                    try (InputStream in = getClass()
                                            .getResourceAsStream("/maps/icons/" + depositIcon)) {
                                        if (in == null)
                                            continue;
                                        Image img = new Image(in, depScale, depScale, true, true);
                                        ImageView iv = new ImageView(img);
                                        iv.setPreserveRatio(true);
                                        iv.setSmooth(true);
                                        iv.setFitWidth(depScale);
                                        iv.setFitHeight(depScale);
                                        iv.setTranslateY(-1);
                                        sp.getChildren().add(iv);
                                    } catch (Exception ex) {
                                        System.err.println(
                                                "[MINIMAP] Errore caricando icona affioramento " + depositIcon + ": "
                                                        + ex.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }

                break;
            }

            case "players_in_view": {
                if (r.has("players") && r.get("players").isJsonArray()) {
                    updateMiniMapWithPlayers(r.getAsJsonArray("players"));
                }
                break;
            }

            case "players_on_tile": {
                if (r.has("players") && r.get("players").isJsonArray()) {
                    StringBuilder sb = new StringBuilder("Qui vedi:\n");
                    for (JsonElement p : r.getAsJsonArray("players")) {
                        JsonObject pj = p.getAsJsonObject();
                        String name = pj.has("name") ? pj.get("name").getAsString() : "???";
                        String icon = pj.has("icon") ? pj.get("icon").getAsString() : "default.gif";
                        int px = pj.has("x") ? pj.get("x").getAsInt() : -1;
                        int py = pj.has("y") ? pj.get("y").getAsInt() : -1;

                        sb.append("- ").append(name).append(" (").append(icon).append(" @ ").append(px).append(",")
                                .append(py).append(")\n");
                    }
                    appendWithAnsiColors(sb.toString());

                    // 🔹 Aggiorna overlay sulla minimappa
                    if (r.has("players") && r.get("players").isJsonArray()) {
                        JsonArray arr = r.getAsJsonArray("players");
                        updateMiniMapWithPlayers(arr);
                    }
                }
                break;
            }

            case "room": {
                String roomTitle;
                if (r.has("name")) {
                    roomTitle = r.get("name").getAsString();
                } else {
                    roomTitle = r.has("id") ? r.get("id").getAsString() : "(stanza)";
                }
                appendWithAnsiColors("--- Stanza: " + roomTitle + " ---");

                if (r.has("desc")) {
                    appendWithAnsiColors(r.get("desc").getAsString());
                }

                if (r.has("exits") && r.get("exits").isJsonObject()) {
                    JsonObject exits = r.getAsJsonObject("exits");

                    if (exits.entrySet().isEmpty()) {
                        appendWithAnsiColors("Uscite: nessuna");
                    } else {
                        StringBuilder sbExits = new StringBuilder("Uscite: ");
                        for (Map.Entry<String, JsonElement> e : exits.entrySet()) {
                            String dir = normalizeDir(e.getKey());
                            String dest = e.getValue().getAsString().trim();
                            sbExits.append(dir).append(" → ").append(dest).append("  ");
                        }
                        appendWithAnsiColors(sbExits.toString());
                    }
                } else {
                    appendWithAnsiColors("Uscite: (dato mancante)");
                }

                if (r.has("players") && r.get("players").isJsonArray()) {
                    JsonArray players = r.getAsJsonArray("players");
                    for (JsonElement p : players) {
                        appendWithAnsiColors(p.getAsString()); // 👈 ogni riga già pronta dal server
                    }
                }

                break;
            }

            case "chat": {
                String from = r.has("from") ? r.get("from").getAsString() : "???";
                String msg = r.has("message") ? r.get("message").getAsString() : "";
                appendWithAnsiColors(from + ": " + msg);
                break;
            }

            case "who": {
                StringBuilder sb = new StringBuilder("Giocatori online:\n");
                if (r.has("players") && r.get("players").isJsonArray()) {
                    for (JsonElement e : r.getAsJsonArray("players")) {
                        JsonObject p = e.getAsJsonObject();
                        // usa il campo "name" che il server invia
                        String name = p.has("name") ? p.get("name").getAsString() : "???";
                        String room = p.has("room") ? p.get("room").getAsString() : "?";
                        sb.append("- ").append(name).append(" (").append(room).append(")\n");
                    }
                } else {
                    sb.append("(nessuno o dato mancante)\n");
                }
                appendWithAnsiColors(sb.toString());
                break;
            }

            case "error": {
                String msg = r.has("message") ? r.get("message").getAsString() : "";
                appendWithAnsiColors("[errore] " + msg);
                break;
            }

            case "auth": {
                String msg = r.has("message") ? r.get("message").getAsString() : "";
                appendWithAnsiColors("[auth] " + msg);
                break;
            }

            case "status":
            case "vitals": {
                int health = r.has("health") ? r.get("health").getAsInt() : 100;
                int stamina = r.has("stamina") ? r.get("stamina").getAsInt() : 100;
                int hunger = r.has("hunger") ? r.get("hunger").getAsInt() : 100;
                int thirst = r.has("thirst") ? r.get("thirst").getAsInt() : 100;
                boolean immortal = r.has("immortal") && r.get("immortal").getAsBoolean();

                Platform.runLater(() -> {
                    boolean changed = (this.immortalMode != immortal);
                    this.immortalMode = immortal;
                    if (changed)
                        applyFrozenUI(immortal); // <<< AGGIUNTO
                    updateStatus(health, stamina, hunger, thirst);
                });
                break;
            }

            case "charcreate_required": {
                String msg = r.has("message") ? r.get("message").getAsString() : "Creazione personaggio richiesta!";
                appendWithAnsiColors("[server] " + msg);

                JsonObject spec = r.has("spec") && r.get("spec").isJsonObject()
                        ? r.getAsJsonObject("spec")
                        : new JsonObject();

                Platform.runLater(() -> showCharCreationUI(spec));
                break;
            }

            case "charcreated": {
                String name = r.has("name") ? r.get("name").getAsString() : "?";
                String race = r.has("race") ? r.get("race").getAsString() : "?";
                String clazz = r.has("clazz") ? r.get("clazz").getAsString() : "?";

                appendWithAnsiColors("[server] Personaggio creato: " + name + " (" + race + " " + clazz + ")");

                if (r.has("final_stats") && r.get("final_stats").isJsonObject()) {
                    JsonObject st = r.getAsJsonObject("final_stats");
                    StringBuilder sb = new StringBuilder("Statistiche iniziali:\n");
                    for (Map.Entry<String, JsonElement> e : st.entrySet()) {
                        sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().getAsInt()).append("\n");
                    }
                    appendWithAnsiColors(sb.toString());
                }

                // === 🔹 Avviso iniziale ===
                appendWithAnsiColors("§eCreazione completata! Il gioco si riavvierà tra pochi secondi...§r\n");

                // === 🔹 Attendi 3 secondi PRIMA della disconnessione automatica ===
                Timeline delayBeforeDisc = new Timeline(new KeyFrame(Duration.seconds(3), ev1 -> {

                    appendWithAnsiColors("§7Disconnessione automatica in corso...§r\n");

                    Timeline autoDisc = new Timeline(new KeyFrame(Duration.seconds(2), ev2 -> {
                        try {
                            closing = true;

                            // invia "exit" al server
                            if (out != null) {
                                JsonObject cmd = new JsonObject();
                                cmd.addProperty("type", "cmd");
                                cmd.addProperty("text", "exit");
                                out.println(gson.toJson(cmd));
                            }

                            if (socket != null && !socket.isClosed()) {
                                socket.close();
                            }
                        } catch (IOException ignored) {
                        }

                        socket = null;
                        in = null;
                        out = null;

                        // 🔹 torna alla schermata di login
                        Platform.runLater(this::showLogin);
                    }));
                    autoDisc.setCycleCount(1);
                    autoDisc.play();

                }));
                delayBeforeDisc.setCycleCount(1);
                delayBeforeDisc.play();

                break;
            }

            case "charlist": {
                if (r.has("characters") && r.get("characters").isJsonArray()) {
                    JsonArray arr = r.getAsJsonArray("characters");

                    if (arr.size() == 0) {
                        appendWithAnsiColors("[server] Nessun personaggio trovato.");
                    } else {
                        StringBuilder sb = new StringBuilder("[server] Personaggi disponibili:\n");

                        for (JsonElement el : arr) {
                            JsonObject c = el.getAsJsonObject();
                            String id = c.has("id") ? String.valueOf(c.get("id").getAsInt()) : "?";
                            String name = c.has("name") ? c.get("name").getAsString() : "?";
                            String race = c.has("race") ? c.get("race").getAsString() : "?";
                            String clazz = c.has("clazz") ? c.get("clazz").getAsString() : "?";

                            sb.append("- ID: ").append(id)
                                    .append(" | ").append(name)
                                    .append(" (").append(race).append(" ").append(clazz).append(")\n");
                        }

                        appendWithAnsiColors(sb.toString());

                        Platform.runLater(() -> {
                            VBox box = new VBox(10);
                            box.setPadding(new Insets(15));

                            final Button[] firstBtn = new Button[1]; // per focus automatico

                            for (JsonElement el : arr) {
                                JsonObject c = el.getAsJsonObject();
                                final int charId = c.has("id") ? c.get("id").getAsInt() : -1;
                                String name = c.has("name") ? c.get("name").getAsString() : "?";
                                String race = c.has("race") ? c.get("race").getAsString() : "?";
                                String clazz = c.has("clazz") ? c.get("clazz").getAsString() : "?";

                                Button btn = new Button(name + " (" + race + " " + clazz + ")");
                                btn.setMaxWidth(Double.MAX_VALUE);

                                btn.setOnAction(ev -> {
                                    JsonObject sel = new JsonObject();
                                    sel.addProperty("type", "charselect");
                                    sel.addProperty("id", charId);
                                    out.println(gson.toJson(sel));
                                    ((Stage) btn.getScene().getWindow()).close();
                                });

                                box.getChildren().add(btn);

                                if (firstBtn[0] == null) {
                                    firstBtn[0] = btn; // salva il primo bottone per focus
                                }
                            }

                            // 🔹 aggiungi pulsante Annulla
                            Button cancelBtn = new Button("Annulla");
                            cancelBtn.setMaxWidth(Double.MAX_VALUE);
                            cancelBtn.setOnAction(ev -> {
                                // 🔹 chiudi connessione se ancora aperta
                                try {
                                    if (socket != null && !socket.isClosed()) {
                                        socket.close();
                                    }
                                } catch (IOException ignored) {
                                }

                                socket = null;
                                in = null;
                                out = null;

                                ((Stage) cancelBtn.getScene().getWindow()).close(); // chiude finestra selezione
                                showLogin(); // torna al login (centrata se aggiungi centerOnScreen in showLogin)
                            });
                            box.getChildren().add(cancelBtn);

                            Stage dialog = new Stage();
                            dialog.setTitle("Seleziona personaggio");

                            box.setStyle("-fx-background-color: #1b1b1b;"); // 🔹 sfondo scuro
                            for (javafx.scene.Node node : box.getChildren()) {
                                if (node instanceof Label) {
                                    node.setStyle("-fx-text-fill: #e0e0c0;"); // testo chiaro
                                }
                                if (node instanceof Button) {
                                    node.setStyle("-fx-background-color: #333333; -fx-text-fill: #e0e0c0;");
                                }
                            }

                            Scene sc = new Scene(box);
                            var css = getClass().getResource("/dark.css");
                            if (css != null) {
                                sc.getStylesheets().add(css.toExternalForm()); // 🔹 usa anche il css globale dark
                            }

                            dialog.setScene(sc);
                            dialog.setResizable(false);
                            dialog.initOwner(primary);

                            dialog.centerOnScreen(); // 👈 centra sullo schermo
                            dialog.sizeToScene(); // 👈 auto-adatta dimensione al contenuto
                            dialog.show();

                            if (firstBtn[0] != null) {
                                Platform.runLater(() -> firstBtn[0].requestFocus());
                            }

                        });
                    }
                } else {
                    appendWithAnsiColors("[server] Lista personaggi non ricevuta correttamente.");
                }
                break;
            }

            case "charselected": {
                String name = r.has("name") ? r.get("name").getAsString() : "?";
                String race = r.has("race") ? r.get("race").getAsString() : "?";
                String clazz = r.has("clazz") ? r.get("clazz").getAsString() : "?";

                appendWithAnsiColors("[server] Personaggio selezionato: " + name + " (" + race + " " + clazz + ")");

                break;
            }

            // 🔹 nuovo messaggio dal server per mostrare schermata Info
            case "charinfo": {
                // helper locali per leggere stringhe e oggetti con chiavi alternative
                java.util.function.Function<String[], String> getStr = (String[] keys) -> {
                    for (String k : keys) {
                        if (r.has(k) && !r.get(k).isJsonNull()) {
                            try {
                                return r.get(k).getAsString();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return null;
                };
                java.util.function.Function<String[], com.google.gson.JsonObject> getObj = (String[] keys) -> {
                    for (String k : keys) {
                        if (r.has(k) && r.get(k).isJsonObject()) {
                            return r.getAsJsonObject(k);
                        }
                    }
                    return new com.google.gson.JsonObject();
                };

                // leggi con alias tolleranti
                String name = getStr.apply(new String[] { "name" });
                String race = getStr.apply(new String[] { "race", "razza" });
                String clazz = getStr.apply(new String[] { "clazz", "class", "classe" });
                String adjective = getStr.apply(new String[] { "adjective", "aggettivo", "adj" });
                String description = getStr.apply(new String[] { "description", "descrizione" });

                // fallback visivi se mancanti
                if (name == null || name.isBlank())
                    name = "?";
                if (race == null || race.isBlank())
                    race = "?";
                if (clazz == null || clazz.isBlank())
                    clazz = "?";
                if (adjective == null)
                    adjective = "";
                if (description == null)
                    description = "";

                com.google.gson.JsonObject stats = getObj.apply(new String[] { "stats" });
                com.google.gson.JsonObject vitals = getObj.apply(new String[] { "vitals" });

                showCharInfoUI(name, race, clazz, adjective, description, stats, vitals);
                break;
            }

            default: {
                if (r.has("text")) {
                    appendWithAnsiColors(r.get("text").getAsString());
                } else {
                    appendWithAnsiColors("[raw] " + r.toString());
                }
            }

        }
    }

    // Aggiorna la velocità se presente nel payload; stampa solo quando cambia
    private void maybeUpdateMovementSpeed(JsonObject r) {
        if (r == null)
            return;

        // prova una serie di chiavi comuni
        String[] keys = { "movement_speed", "speed", "movement", "move" };
        String found = null;
        for (String k : keys) {
            if (r.has(k) && r.get(k).isJsonPrimitive()) {
                String v = r.get(k).getAsString();
                if (v != null && !v.isBlank()) {
                    found = v.trim().toLowerCase();
                    break;
                }
            }
        }
        if (found == null)
            return;

        // aggiorna stato locale
        movementSpeed = found;

        // stampa solo se è cambiata da ultima stampa
        if (!found.equals(lastPrintedSpeed)) {
            appendWithAnsiColors("Stai attualmente " + found + ".\n");
            appendWithAnsiColors("Puoi scegliere: passeggia / cammina / marcia / corri\n\n");
            lastPrintedSpeed = found;
        }
        System.out.println("[DEBUG] Messaggio contiene movement_speed: " + r.toString());
    }

    private void updateMiniMapIso(JsonObject payload, JsonArray rows, int tileSize, int view, int cx, int cy,
            String playerIcon) {
        minimapPane.getChildren().clear();
        isoCells.clear();

        final int TILE_W = (int) Math.round(TILE_SIZE * MINIMAP_ZOOM);
        final int TILE_H = (int) Math.round(TILE_SIZE * MINIMAP_ZOOM);

        minimapPane.setPrefSize(view * TILE_W, view * TILE_H);

        // === 1) Pavimenti ===
        for (int y = 0; y < rows.size(); y++) {
            JsonArray row = rows.get(y).getAsJsonArray();
            for (int x = 0; x < row.size(); x++) {
                int floorId = row.get(x).getAsInt();

                StackPane cell = new StackPane();
                cell.setMinSize(TILE_W, TILE_H);
                cell.setPrefSize(TILE_W, TILE_H);
                cell.setMaxSize(TILE_W, TILE_H);
                cell.setLayoutX(x * TILE_W);
                cell.setLayoutY(y * TILE_H);

                // mappa ID -> sprite pavimento
                String floorFile = switch (floorId) {
                    case 900 -> "0Y.gif"; // base
                    case 901 -> "stone.gif"; // esempio (adatta ai tuoi file reali)
                    case 902 -> "wood.gif"; // esempio
                    default -> null;
                };

                if (floorFile != null) {
                    try (InputStream in = getClass().getResourceAsStream("/maps/client/" + floorFile)) {
                        if (in != null) {
                            Image floorImg = new Image(in);
                            ImageView floorView = new ImageView(floorImg);
                            // crop centrale 19x19 stile Isylea
                            floorView.setViewport(new Rectangle2D(13, 13, 19, 19));
                            floorView.setFitWidth(TILE_W);
                            floorView.setFitHeight(TILE_H);
                            floorView.setPreserveRatio(false);
                            floorView.setSmooth(false);
                            floorView.setCache(true);
                            cell.getChildren().add(floorView);
                        }
                    } catch (Exception ex) {
                        System.err
                                .println("[MINIMAP] Errore caricando pavimento " + floorFile + ": " + ex.getMessage());
                    }
                }
                minimapPane.getChildren().add(cell);
                isoCells.put(keyXY(x, y), cell);
            }
        }

        // === 2) Muri / Archi / Porte ===
        if (payload.has("walls") && payload.get("walls").isJsonArray()) {
            JsonArray walls = payload.getAsJsonArray("walls");
            for (JsonElement el : walls) {
                JsonObject w = el.getAsJsonObject();
                int dx = w.get("dx").getAsInt();
                int dy = w.get("dy").getAsInt();
                String dir = w.get("dir").getAsString();
                int wallId = w.get("tileId").getAsInt();

                // mappa ID -> sprite muri/archi/porte
                String wallFile = switch (wallId) {
                    case 910 -> "wall_brick.gif";
                    case 911 -> "0archn.gif";
                    case 912 -> "0archw.gif";
                    case 913 -> "0arche.gif";
                    case 914 -> "0archs.gif";
                    case 915 -> "0closdoorn.gif";
                    case 916 -> "0closdoors.gif";
                    case 917 -> "0closdoore.gif";
                    case 918 -> "0closdoorw.gif";
                    case 919 -> "0opendoorn.gif";
                    case 920 -> "0opendoors.gif";
                    case 921 -> "0opendoore.gif";
                    case 922 -> "0opendoorw.gif";
                    case 923 -> "0walln.gif";
                    case 924 -> "0walls.gif";
                    case 925 -> "0walle.gif";
                    case 926 -> "0wallw.gif";
                    default -> null;
                };

                if (wallFile == null)
                    continue;

                int relX = view / 2 + dx;
                int relY = view / 2 + dy;

                StackPane cell = isoCells.get(keyXY(relX, relY));
                if (cell == null)
                    continue;

                try (InputStream in = getClass().getResourceAsStream("/maps/client/" + wallFile)) {
                    if (in != null) {
                        Image img = new Image(in);
                        ImageView iv = new ImageView(img);

                        // niente scaling
                        iv.setPreserveRatio(false);
                        iv.setSmooth(false);

                        double K = MINIMAP_ZOOM;
                        switch (dir.toLowerCase()) {
                            case "nord", "n" -> {
                                iv.setTranslateX(-4 * K); // positivo destra negativo sinistra
                                iv.setTranslateY(-14 * K); // positivo giù negativo su
                            }
                            case "sud", "s" -> {
                                iv.setTranslateX(-4 * K);
                                iv.setTranslateY(-3 * K);
                            }
                            case "est", "e" -> {
                                iv.setTranslateX(-3 * K);
                                iv.setTranslateY(-4 * K);
                            }
                            case "ovest", "o", "w" -> {
                                iv.setTranslateX(-13 * K);
                                iv.setTranslateY(-3 * K);
                            }
                        }
                        cell.getChildren().add(iv);
                    }
                } catch (Exception ex) {
                    System.err.println("[MINIMAP] Errore caricando muro " + wallFile + ": " + ex.getMessage());
                }
            }

            // === 3) Overlay tetti (se payload contiene roofOverlay) ===
            if (payload.has("roofOverlay") && payload.get("roofOverlay").isJsonArray() && isOutdoor) {
                JsonArray overlay = payload.getAsJsonArray("roofOverlay");
                for (int y = 0; y < overlay.size(); y++) {
                    JsonArray row = overlay.get(y).getAsJsonArray();
                    for (int x = 0; x < row.size(); x++) {
                        int tid = row.get(x).getAsInt();
                        if (tid == 0)
                            continue;

                        StackPane cell = isoCells.get(keyXY(x, y));
                        if (cell == null)
                            continue;

                        Image img = TileRegistry.getImage(tid, "city");
                        if (img != null) {
                            ImageView roofView = new ImageView(img);
                            roofView.setViewport(VP_FLOOR); // riuso il crop 19x19
                            roofView.setFitWidth(TILE_W);
                            roofView.setFitHeight(TILE_H);
                            roofView.setPreserveRatio(false);
                            roofView.setSmooth(false);
                            roofView.setCache(true);
                            cell.getChildren().add(roofView);
                        }
                    }
                }
            }

        }

        // === 3) Player al centro ===
        int center = view / 2;
        StackPane centerCell = isoCells.get(keyXY(center, center));
        if (centerCell != null) {
            InputStream iconStream = getClass().getResourceAsStream("/maps/icons/" + playerIcon);
            if (iconStream == null)
                iconStream = getClass().getResourceAsStream("/maps/icons/default.gif");
            if (iconStream != null) {
                Image pimg = new Image(iconStream);
                ImageView playerView = new ImageView(pimg);
                playerView.setFitWidth(TILE_W);
                playerView.setFitHeight(TILE_H);
                playerView.setPreserveRatio(false);
                playerView.setSmooth(false);
                centerCell.getChildren().add(playerView);
            }
        }
    }

    // helper: mappa codice → nome file completo
    private String wallFile(char code, String dir) {
        return switch (code) {
            case '1' -> "0wall" + dir + ".gif";
            case '2' -> "0arch" + dir + ".gif";
            case '3' -> "0door" + dir + ".gif";
            default -> null;
        };
    }

    private Image loadIsoSpriteForFloor(char floorChar) {
        // mappa la cifra del pavimento a un file in /maps/client
        // Se i tuoi pavimenti “di città” sono tutti in un’unica sprite (es. 0Y.gif),
        // usala; altrimenti mappa per codice.
        String filename = "/maps/client/0Y.gif"; // sprite standard isometrica 45x45
        InputStream in = getClass().getResourceAsStream(filename);
        if (in == null) {
            System.err.println("[MINIMAP] Pavimento iso non trovato: " + filename + " (fallback /maps/icons/0.gif)");
            in = getClass().getResourceAsStream("/maps/icons/0.gif");
        }
        return new Image(in);
    }

    // Usa SEMPRE il nome file completo (es. "0archn.gif", "0walls.gif", ecc.)
    private void addWallToCell(StackPane cell, String file, String dir, int TILE_W, int TILE_H) {
        try (InputStream in = getClass().getResourceAsStream("/maps/client/" + file)) {
            if (in == null) {
                System.err.println("[MINIMAP] Sprite muro non trovato: " + file);
                return;
            }
            Image img = new Image(in);
            ImageView iv = new ImageView(img);

            // Crop viewport a seconda della direzione
            switch (dir.toLowerCase()) {
                case "nord", "n" -> {
                    iv.setViewport(new Rectangle2D(0, 0, 45, 13)); // arco nord 45x13
                    iv.setFitWidth(TILE_W);
                    iv.setFitHeight(13 * TILE_H / 19.0); // proporzione
                    iv.setTranslateY(-10);
                }
                case "sud", "s" -> {
                    iv.setViewport(new Rectangle2D(0, 32, 45, 13)); // arco sud 45x13
                    iv.setFitWidth(TILE_W);
                    iv.setFitHeight(13 * TILE_H / 19.0);
                    iv.setTranslateY(+6);
                }
                case "est", "e" -> {
                    iv.setViewport(new Rectangle2D(32, 0, 13, 45)); // arco est 13x45
                    iv.setFitWidth(13 * TILE_W / 19.0);
                    iv.setFitHeight(TILE_H);
                    iv.setTranslateX(+10);
                }
                case "ovest", "o", "w" -> {
                    iv.setViewport(new Rectangle2D(0, 0, 13, 45)); // arco ovest 13x45
                    iv.setFitWidth(13 * TILE_W / 19.0);
                    iv.setFitHeight(TILE_H);
                    iv.setTranslateX(-10);
                }
            }

            iv.setPreserveRatio(false);
            iv.setSmooth(true);
            cell.getChildren().add(iv);
        } catch (Exception e) {
            System.err.println("[MINIMAP] Errore caricando muro: " + e.getMessage());
        }
    }

    /**
     * Fallback robusto: se il manifest non fornisce il viewport, usiamo
     * le fasce standard del foglio 45x45 stile Isylea.
     * - Centro (pavimento) = (13,13,19,19)
     * - Nord = (13,0,19,13)
     * - Sud = (13,32,19,13)
     * - Est = (32,13,13,19)
     * - Ovest= (0,13,13,19)
     */
    private Rectangle2D viewportFromName(String fileName) {
        String f = fileName.toLowerCase();
        if (f.endsWith("n.gif"))
            return new Rectangle2D(13, 0, 19, 13);
        if (f.endsWith("s.gif"))
            return new Rectangle2D(13, 32, 19, 13);
        if (f.endsWith("e.gif"))
            return new Rectangle2D(32, 13, 13, 19);
        if (f.endsWith("w.gif"))
            return new Rectangle2D(0, 13, 13, 19);
        // se non riconosciamo la direzione, non forziamo nulla
        return null;
    }

    private String suffixForDir(String dir) {
        switch (dir) {
            case "n":
            case "nord":
                return "n";
            case "s":
            case "sud":
                return "s";
            case "e":
            case "est":
                return "e";
            case "o":
            case "ovest":
            case "w":
                return "o";
        }
        return "n";
    }

    private int tryParseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    // Esempio: restituisce il sub-rect 45x45 da scalare a 19x19
    public static Rectangle2D getWallViewport(int tileId, String ctx, String dir) {
        dir = dir.toLowerCase();
        // qui metti la tua tabella di crop; numeri d’esempio!
        switch (dir) {
            case "n":
            case "nord":
                return new Rectangle2D(0, 0, 45, 45); // es. frame per il muro Nord
            case "e":
            case "est":
                return new Rectangle2D(45, 0, 45, 45); // Est
            case "s":
            case "sud":
                return new Rectangle2D(90, 0, 45, 45); // Sud
            case "o":
            case "ovest":
            case "w":
                return new Rectangle2D(135, 0, 45, 45); // Ovest
            default:
                return null;
        }
    }

    // disegna muro/arco/porta in base al carattere e alla direzione
    private void drawWallLike(StackPane cell, char code, String dir, int TILE_W, int TILE_H) {
        if (code == '0')
            return; // nessun muro

        String file = code + ".gif"; // esempio: "1.gif", "A.gif", ecc.
        InputStream in = getClass().getResourceAsStream("/maps/client/" + file);
        if (in == null)
            return;

        Image img = new Image(in);
        ImageView iv = new ImageView(img);
        iv.setFitWidth(TILE_W);
        iv.setFitHeight(TILE_H);
        iv.setPreserveRatio(false);
        iv.setSmooth(true);

        // offset direzionali (da WGraphic)
        switch (dir) {
            case "nord" -> {
                iv.setTranslateX(-2);
                iv.setTranslateY(-10);
            }
            case "sud" -> {
                iv.setTranslateX(-2);
                iv.setTranslateY(+6);
            }
            case "est" -> {
                iv.setTranslateX(+8);
                iv.setTranslateY(-2);
            }
            case "ovest" -> {
                iv.setTranslateX(-12);
                iv.setTranslateY(-2);
            }
        }

        cell.getChildren().add(iv);
    }

    private void updateMiniMapWithPlayers(JsonArray players) {
        if (players == null)
            return;

        int center = currentViewSize / 2;
        int view = currentViewSize;

        if (isoMode) {
            // === modalità città/dungeon: isoCells ===
            final int scaledTile = (int) Math.round(TILE_SIZE * MINIMAP_ZOOM);
            final int TILE_W = scaledTile;
            final int TILE_H = scaledTile;

            for (JsonElement e : players) {
                JsonObject pj = e.getAsJsonObject();
                int dx = pj.has("dx") ? pj.get("dx").getAsInt() : 0;
                int dy = pj.has("dy") ? pj.get("dy").getAsInt() : 0;
                String icon = pj.has("icon") ? pj.get("icon").getAsString() : "default.gif";

                int relX = center + dx;
                int relY = center + dy;
                if (relX < 0 || relX >= view || relY < 0 || relY >= view)
                    continue;

                StackPane cell = isoCells.get(keyXY(relX, relY));
                if (cell == null)
                    continue;

                InputStream iconStream = getClass().getResourceAsStream("/maps/icons/" + icon);
                if (iconStream == null) {
                    iconStream = getClass().getResourceAsStream("/maps/icons/default.gif");
                }

                if (iconStream != null) {
                    Image img = new Image(iconStream);
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(TILE_W);
                    iv.setFitHeight(TILE_H);
                    iv.setPreserveRatio(false);
                    iv.setSmooth(true);

                    cell.getChildren().add(iv);
                }
            }
        } else {
            // === modalità overworld: griglia ortogonale ===
            for (JsonElement e : players) {
                JsonObject pj = e.getAsJsonObject();
                int dx = pj.has("dx") ? pj.get("dx").getAsInt() : 0;
                int dy = pj.has("dy") ? pj.get("dy").getAsInt() : 0;
                String icon = pj.has("icon") ? pj.get("icon").getAsString() : "default.gif";

                int relX = center + dx;
                int relY = center + dy;
                if (relX < 0 || relX >= view || relY < 0 || relY >= view)
                    continue;

                InputStream iconStream = getClass().getResourceAsStream("/maps/icons/" + icon);
                if (iconStream == null) {
                    iconStream = getClass().getResourceAsStream("/maps/icons/default.gif");
                }

                if (iconStream != null) {
                    Image img = new Image(iconStream, TILE_SIZE * MINIMAP_ZOOM, TILE_SIZE * MINIMAP_ZOOM, false, true);
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(TILE_SIZE * MINIMAP_ZOOM);
                    iv.setFitHeight(TILE_SIZE * MINIMAP_ZOOM);

                    for (javafx.scene.Node node : minimapGrid.getChildren()) {
                        Integer col = GridPane.getColumnIndex(node);
                        Integer row = GridPane.getRowIndex(node);
                        if (col != null && row != null && col == relX && row == relY && node instanceof StackPane) {
                            ((StackPane) node).getChildren().add(iv);
                        }
                    }
                }
            }
        }
    }

    // 🔹 Overlay altri giocatori in iso mode (città/dungeon → /maps/client/)
    private void overlayOthersIso(JsonArray others, int cx, int cy, int view) {
        if (others == null)
            return;

        int center = view / 2;
        final int scaledTile = (int) Math.round(TILE_SIZE * MINIMAP_ZOOM);
        final int TILE_W = scaledTile;
        final int TILE_H = scaledTile;

        for (JsonElement el : others) {
            JsonObject pj = el.getAsJsonObject();
            int ox = pj.has("x") ? pj.get("x").getAsInt() : Integer.MIN_VALUE;
            int oy = pj.has("y") ? pj.get("y").getAsInt() : Integer.MIN_VALUE;
            String icon = pj.has("icon") ? pj.get("icon").getAsString() : "0Y.gif"; // default iso
            if (ox == Integer.MIN_VALUE || oy == Integer.MIN_VALUE)
                continue;

            int dx = ox - cx;
            int dy = oy - cy;

            int relX = center + dx;
            int relY = center + dy;

            if (relX < 0 || relX >= view || relY < 0 || relY >= view)
                continue;

            StackPane cell = isoCells.get(keyXY(relX, relY));
            if (cell == null)
                continue;

            // carica sprite iso
            InputStream iconStream = getClass().getResourceAsStream("/maps/client/" + icon);
            if (iconStream == null) {
                iconStream = getClass().getResourceAsStream("/maps/client/0Y.gif");
            }

            if (iconStream != null) {
                Image img = new Image(iconStream);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(TILE_W);
                iv.setFitHeight(TILE_H);
                iv.setPreserveRatio(false);
                iv.setSmooth(true);

                cell.getChildren().add(iv);
            }
        }
    }

    // 4 parametri: la versione "nuova"
    private void overlayOthersOnMiniMap(JsonArray others, int cx, int cy, int view) {
        // qui metti la logica che disegna gli altri giocatori in modalità overworld
        // (è la stessa che avevi già scritto nel blocco grande con GridPane)
        if (others == null)
            return;
        int center = view / 2;

        for (JsonElement e : others) {
            JsonObject pj = e.getAsJsonObject();
            int dx = pj.has("dx") ? pj.get("dx").getAsInt() : 0;
            int dy = pj.has("dy") ? pj.get("dy").getAsInt() : 0;
            String icon = pj.has("icon") ? pj.get("icon").getAsString() : "default.gif";

            int relX = center + dx;
            int relY = center + dy;
            if (relX < 0 || relX >= view || relY < 0 || relY >= view)
                continue;

            InputStream iconStream = getClass().getResourceAsStream("/maps/icons/" + icon);
            if (iconStream == null) {
                iconStream = getClass().getResourceAsStream("/maps/icons/default.gif");
            }
            if (iconStream != null) {
                Image img = new Image(iconStream, TILE_SIZE * MINIMAP_ZOOM, TILE_SIZE * MINIMAP_ZOOM, false, true);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(TILE_SIZE * MINIMAP_ZOOM);
                iv.setFitHeight(TILE_SIZE * MINIMAP_ZOOM);

                for (javafx.scene.Node node : minimapGrid.getChildren()) {
                    Integer col = GridPane.getColumnIndex(node);
                    Integer row = GridPane.getRowIndex(node);
                    if (col != null && row != null && col == relX && row == relY && node instanceof StackPane sp) {
                        sp.getChildren().add(iv);
                    }
                }
            }
        }
    }

    // 3 parametri: wrapper che richiama il 4 parametri con currentViewSize
    private void overlayOthersOnMiniMap(JsonArray others, int cx, int cy) {
        overlayOthersOnMiniMap(others, cx, cy, currentViewSize);
    }

    private void showCharInfoUI(String name, String race, String clazz, String adjective, String description,
            JsonObject stats, JsonObject vitals) {
        Platform.runLater(() -> {
            Stage dialog = new Stage();
            dialog.setTitle("Scheda Personaggio");

            VBox root = new VBox(20);
            root.setPadding(new Insets(20));
            root.setStyle("-fx-background-color: #1b1b1b;");

            // --- Sezione Descrizione ---
            VBox descBox = new VBox(6);
            descBox.setPadding(new Insets(12));
            descBox.setStyle("-fx-background-color: #202020; -fx-background-radius: 12;");

            Label descTitle = new Label("Descrizione");
            descTitle.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 14px; -fx-font-weight: bold;");

            Label descText = new Label(description != null && !description.isBlank()
                    ? description
                    : "Nessuna descrizione impostata.");
            descText.setWrapText(true);
            descText.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px;");

            // --- Pulsante per modificare la descrizione ---
            Button editDescBtn = new Button("Modifica descrizione");
            editDescBtn.setStyle("-fx-background-color: #444; -fx-text-fill: white; -fx-font-size: 12px;");

            editDescBtn.setOnAction(ev -> {
                Stage editStage = new Stage();
                editStage.setTitle("Modifica descrizione");

                VBox editRoot = new VBox(10);
                editRoot.setPadding(new Insets(15));

                Label info = new Label("Scrivi la nuova descrizione del personaggio:");
                info.setStyle("-fx-text-fill: #dddddd;");

                TextArea area = new TextArea(description);
                area.setWrapText(true);
                area.setPrefRowCount(6);

                Button saveBtn = new Button("Salva");
                saveBtn.setStyle("-fx-background-color: #008080; -fx-text-fill: white; -fx-font-weight: bold;");

                saveBtn.setOnAction(e2 -> {
                    String newDesc = area.getText().trim();
                    if (!newDesc.isEmpty()) {
                        JsonObject cmd = new JsonObject();
                        cmd.addProperty("type", "cmd");
                        cmd.addProperty("text", "cambia descrizione \"" + newDesc + "\"");

                        out.println(gson.toJson(cmd)); // ✅ stesso canale usato dal client
                        descText.setText(newDesc); // aggiorna subito la UI
                    }
                    editStage.close();
                });

                editRoot.getChildren().addAll(info, area, saveBtn);
                Scene sc = new Scene(editRoot, 500, 300);
                sc.getStylesheets().add("dark.css"); // ✅ stesso metodo usato nelle altre finestre

                editStage.setScene(sc);
                editStage.setResizable(false);
                editStage.centerOnScreen();
                editStage.show();

            });

            descBox.getChildren().addAll(descTitle, descText, editDescBtn);

            // --- Card intestazione ---
            VBox header = new VBox(8);
            header.setPadding(new Insets(12));
            header.setStyle(
                    "-fx-background-color: #2c2c2c; -fx-border-color: #444; -fx-border-radius: 8; -fx-background-radius: 8;");

            Label lblName = new Label("Nome: " + name);
            lblName.setStyle("-fx-text-fill: #f5f5f5; -fx-font-size: 20px; -fx-font-weight: bold;");

            Label lblRace = new Label("Razza: " + race);
            lblRace.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 15px;");

            Label lblAdj = new Label(
                    "Aggettivo: " + (adjective != null && !adjective.isBlank() ? adjective : "Nessuno"));
            lblAdj.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 15px;");

            Label lblClass = new Label("Classe: " + clazz);
            lblClass.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 15px;");

            header.getChildren().addAll(lblName, lblRace, lblAdj, lblClass);

            // --- mappa icone statistiche ---
            Map<String, String> icons = Map.of(
                    "forza", "💪",
                    "destrezza", "🤸",
                    "volonta", "🧘",
                    "empatia", "❤️",
                    "costituzione", "🛡️",
                    "velocita", "⚡",
                    "intelligenza", "🧠",
                    "taglia", "📏");

            // --- griglia statistiche (layout migliorato e allineato) ---
            GridPane grid = new GridPane();
            grid.setHgap(60);
            grid.setVgap(12);
            grid.setPadding(new Insets(20, 40, 20, 40));

            // imposta due colonne simmetriche
            ColumnConstraints col1 = new ColumnConstraints();
            col1.setPercentWidth(50);
            ColumnConstraints col2 = new ColumnConstraints();
            col2.setPercentWidth(50);
            grid.getColumnConstraints().addAll(col1, col2);

            int rowLeft = 0;
            int rowRight = 0;
            int index = 0;

            for (Map.Entry<String, JsonElement> e : stats.entrySet()) {
                String statName = capitalize(e.getKey());
                int value = e.getValue().getAsInt();
                String icon = icons.getOrDefault(e.getKey().toLowerCase(), "•");

                // Etichetta
                Label statLabel = new Label(icon + " " + statName + ":");
                statLabel.setMinWidth(130);
                statLabel.setPrefWidth(130);
                statLabel.setAlignment(Pos.CENTER_LEFT);
                statLabel.setStyle("-fx-text-fill: #e0e0c0; -fx-font-size: 14px; -fx-font-weight: bold;");

                // Barra di progresso
                String color = getColorForStat(statName);
                ProgressBar bar = new ProgressBar(value / 100.0);
                bar.setPrefWidth(200);
                bar.setMinHeight(14);
                bar.setStyle("-fx-accent: " + color + ";");

                // Valore numerico
                Label statVal = new Label(String.valueOf(value));
                statVal.setPrefWidth(40);
                statVal.setAlignment(Pos.CENTER_RIGHT);
                statVal.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 14px;");

                // Riga composta
                HBox row = new HBox(12, statLabel, bar, statVal);
                row.setAlignment(Pos.CENTER_LEFT);

                if (index % 2 == 0) {
                    grid.add(row, 0, rowLeft++);
                } else {
                    grid.add(row, 1, rowRight++);
                }
                index++;
            }

            // --- sezione vitali ---
            VBox vitalsBox = new VBox(10);
            vitalsBox.setPadding(new Insets(12));
            vitalsBox.setStyle(
                    "-fx-background-color: #2c2c2c; -fx-border-color: #444; -fx-border-radius: 8; -fx-background-radius: 8;");

            Label lblVitals = new Label("Stati vitali");
            lblVitals.setStyle("-fx-text-fill: #f5f5f5; -fx-font-size: 16px; -fx-font-weight: bold;");
            vitalsBox.getChildren().add(lblVitals);

            for (String key : new String[] { "health", "stamina", "hunger", "thirst" }) {
                if (vitals.has(key) && vitals.get(key).isJsonObject()) {
                    JsonObject v = vitals.getAsJsonObject(key);
                    int current = v.has("current") ? v.get("current").getAsInt() : 0;
                    int max = v.has("max") ? v.get("max").getAsInt() : 100;
                    int percent = v.has("percent") ? v.get("percent").getAsInt() : 0;

                    Label l = new Label(capitalize(key) + ": " + current + "/" + max + " (" + percent + "%)");
                    l.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 14px;");
                    vitalsBox.getChildren().add(l);
                }
            }

            // --- pulsante chiudi ---
            Button closeBtn = new Button("Chiudi");
            closeBtn.setOnAction(ev -> dialog.close());

            // --- ordine di visualizzazione ---
            root.getChildren().addAll(header, descBox, grid, vitalsBox, closeBtn);

            // 🔹 finestra più ampia e adattabile
            Scene sc = new Scene(root, 1000, 850); // aumentata larghezza e altezza
            dialog.setScene(sc);
            dialog.setResizable(true); // consenti ridimensionamento manuale
            dialog.centerOnScreen();
            dialog.show();
            descText.setWrapText(true);
            descText.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px;");
            descText.setMaxWidth(Double.MAX_VALUE);
        });
    }

    private void showCharCreationUI(JsonObject spec) {
        Stage dialog = new Stage();
        dialog.setTitle("Creazione personaggio");

        // === COLONNA SINISTRA ===
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(15));

        Label lRazza = new Label("Razza:");
        Label lComunita = new Label("Comunità:");
        Label lSesso = new Label("Sesso:");
        Label lAltezza = new Label("Altezza:");
        Label lPeso = new Label("Peso:");
        Label lAggettivo = new Label("Aggettivo:");
        Label lPrimaria = new Label("Caratteristica primaria:");
        Label lSecondaria = new Label("Caratteristica secondaria:");
        Label lPeggiore = new Label("Caratteristica peggiore:");
        Label lAtt1 = new Label("Attività primaria:");
        Label lAtt2 = new Label("Attività secondaria:");

        ChoiceBox<String> cbRazza = new ChoiceBox<>();
        ChoiceBox<String> cbComunita = new ChoiceBox<>();
        ChoiceBox<String> cbAltezza = new ChoiceBox<>();
        ChoiceBox<String> cbPeso = new ChoiceBox<>();
        ChoiceBox<String> cbAggettivo = new ChoiceBox<>();
        ChoiceBox<String> cbPrimaria = new ChoiceBox<>();
        ChoiceBox<String> cbSecondaria = new ChoiceBox<>();
        ChoiceBox<String> cbPeggiore = new ChoiceBox<>();
        ChoiceBox<String> cbAtt1 = new ChoiceBox<>();
        ChoiceBox<String> cbAtt2 = new ChoiceBox<>();

        // === Sezione scelta icona ===
        Label lIcona = new Label("Icona:");
        ToggleGroup tgIcon = new ToggleGroup();

        HBox iconRow1 = new HBox(8);
        HBox iconRow2 = new HBox(8);
        iconRow1.setAlignment(Pos.CENTER_LEFT);
        iconRow2.setAlignment(Pos.CENTER_LEFT);

        // elenco file icone (puoi aggiungerne altri)
        String[] iconFiles = {
                "332.gif", "329.gif", "341.gif"
        };

        for (int i = 0; i < iconFiles.length; i++) {
            String file = iconFiles[i];
            InputStream in = getClass().getResourceAsStream("/maps/icons/" + file);
            if (in == null)
                continue;

            Image img = new Image(in, 28, 28, true, true);
            ImageView iv = new ImageView(img);

            RadioButton rb = new RadioButton();
            rb.setToggleGroup(tgIcon);
            rb.setGraphic(iv);
            rb.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            rb.setUserData(file);
            rb.setStyle("-fx-cursor: hand;");

            if (i < 9)
                iconRow1.getChildren().add(rb);
            else
                iconRow2.getChildren().add(rb);

            // prima icona selezionata di default
            if (i == 0)
                rb.setSelected(true);
        }

        // contenitore icone
        VBox iconBox = new VBox(4, lIcona, iconRow1, iconRow2);
        iconBox.setAlignment(Pos.CENTER_LEFT);

        // Riempimento valori base
        cbRazza.getItems().addAll("Umana", "Elfica", "Nanica", "Orca");
        cbComunita.getItems().addAll("Khenam", "Aeloria", "Toran", "Nimdar");
        cbAltezza.getItems().addAll("Bassa", "Normale", "Alta");
        cbPeso.getItems().addAll("Magro", "Normale", "Robusto");
        cbPrimaria.getItems().addAll("Forza", "Intelligenza", "Destrezza", "Costituzione", "Volontà", "Velocità");
        cbSecondaria.getItems().addAll(cbPrimaria.getItems());
        cbPeggiore.getItems().addAll(cbPrimaria.getItems());
        cbAtt1.getItems().addAll("Studioso", "Guerriero", "Avventuriero", "Cacciatore", "Artigiano");
        cbAtt2.getItems().addAll("Avventuriero", "Esploratore", "Sacerdote", "Mercante");
        cbAggettivo.getItems().addAll("sbilenco", "curioso", "strano", "misterioso", "torvo", "allegro", "burbero",
                "spensierato", "scostante", "silenzioso", "affamato", "stanco", "astuto", "irrequieto", "pensieroso");

        cbRazza.getSelectionModel().selectFirst();
        cbComunita.getSelectionModel().selectFirst();
        cbAltezza.getSelectionModel().select("Normale");
        cbPeso.getSelectionModel().select("Normale");
        cbPrimaria.getSelectionModel().selectFirst();
        cbSecondaria.getSelectionModel().selectFirst();
        cbPeggiore.getSelectionModel().selectFirst();
        cbAtt1.getSelectionModel().selectFirst();
        cbAtt2.getSelectionModel().selectFirst();
        cbAggettivo.getSelectionModel().selectFirst();

        // RadioButtons per sesso
        ToggleGroup tgSesso = new ToggleGroup();
        RadioButton rbMaschio = new RadioButton("Maschio");
        RadioButton rbFemmina = new RadioButton("Femmina");
        rbMaschio.setToggleGroup(tgSesso);
        rbFemmina.setToggleGroup(tgSesso);
        rbMaschio.setSelected(true);

        HBox sessoBox = new HBox(10, rbMaschio, rbFemmina);
        sessoBox.setAlignment(Pos.CENTER_LEFT);

        // Aggiungi le righe
        int r = 0;
        grid.add(lRazza, 0, r);
        grid.add(cbRazza, 1, r++);
        grid.add(lComunita, 0, r);
        grid.add(cbComunita, 1, r++);
        grid.add(lSesso, 0, r);
        grid.add(sessoBox, 1, r++);
        grid.add(lAltezza, 0, r);
        grid.add(cbAltezza, 1, r++);
        grid.add(lPeso, 0, r);
        grid.add(cbPeso, 1, r++);
        grid.add(lAggettivo, 0, r);
        grid.add(cbAggettivo, 1, r++);
        grid.add(lPrimaria, 0, r);
        grid.add(cbPrimaria, 1, r++);
        grid.add(lSecondaria, 0, r);
        grid.add(cbSecondaria, 1, r++);
        grid.add(lPeggiore, 0, r);
        grid.add(cbPeggiore, 1, r++);
        grid.add(lAtt1, 0, r);
        grid.add(cbAtt1, 1, r++);
        grid.add(lAtt2, 0, r);
        grid.add(cbAtt2, 1, r++);
        grid.add(iconBox, 0, r);
        GridPane.setColumnSpan(iconBox, 2);
        r++;

        // === COLONNA DESTRA (descrizione) ===
        TextArea descrizione = new TextArea();
        descrizione.setWrapText(true);
        descrizione.setEditable(false);
        descrizione.setText("""
                Khenam è la capitale dell'attuale regno umano,
                una grande città popolosa e polifunzionale, situata al centro del continente.
                Gli umani sono una razza estremamente varia e versatile, caratterizzata
                da ruoli che spaziano dal colto studioso al soldato o al minatore.
                L'ambientazione è principalmente medievale ma la presenza delle divinità
                è molto sentita e i culti giocano un ruolo fondamentale nella società.
                """);
        descrizione.setPrefWidth(300);
        descrizione.setPrefHeight(240);

        cbComunita.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if ("Khenam".equalsIgnoreCase(newV)) {
                descrizione.setText("""
                        Khenam è la capitale dell'attuale regno umano, una città viva e centrale nel continente.
                        Gli umani sono una razza versatile, dedita a molte arti e mestieri.
                        """);
            }
        });

        // === PULSANTE CREA ===
        Button btnCrea = new Button("Crea il personaggio");
        btnCrea.setStyle(
                "-fx-background-color:#2b2b2b; -fx-text-fill:#e0e0c0; -fx-border-color:#caa85a; -fx-border-width:2;");
        btnCrea.setOnAction(ev -> {
            String sesso = rbMaschio.isSelected() ? "Maschio" : "Femmina";
            String razza = cbRazza.getValue();
            String aggettivo = cbAggettivo.getValue();

            // --- Calcola forma grammaticale corretta ---
            String articolo;
            String razzaDescr;
            if (sesso.equals("Maschio")) {
                switch (razza.toLowerCase()) {
                    case "elfica" -> razzaDescr = "elfo";
                    case "nanica" -> razzaDescr = "nano";
                    case "orca" -> razzaDescr = "orco";
                    default -> razzaDescr = "umano";
                }
                articolo = "Un";
            } else {
                switch (razza.toLowerCase()) {
                    case "elfica" -> razzaDescr = "elfa";
                    case "nanica" -> razzaDescr = "nana";
                    case "orca" -> razzaDescr = "orca";
                    default -> razzaDescr = "umana";
                }
                articolo = (razzaDescr.matches("^[aeiouAEIOU].*")) ? "Un'" : "Una";
            }

            String descrizioneFinale = articolo + " " + razzaDescr + " " + aggettivo;

            JsonObject req = new JsonObject();
            req.addProperty("type", "charcreate");
            req.addProperty("race", razza);
            req.addProperty("community", cbComunita.getValue());
            req.addProperty("sex", sesso);
            req.addProperty("height", cbAltezza.getValue());
            req.addProperty("weight", cbPeso.getValue());
            req.addProperty("primary", cbPrimaria.getValue());
            req.addProperty("secondary", cbSecondaria.getValue());
            req.addProperty("worst", cbPeggiore.getValue());
            req.addProperty("activity1", cbAtt1.getValue());
            req.addProperty("activity2", cbAtt2.getValue());
            req.addProperty("adjective", aggettivo);
            req.addProperty("description", descrizioneFinale);

            // --- Icona selezionata ---
            String iconSel = tgIcon.getSelectedToggle() != null
                    ? tgIcon.getSelectedToggle().getUserData().toString()
                    : "332.gif";
            req.addProperty("icon", iconSel);

            out.println(gson.toJson(req));
            dialog.close();
        });

        // === LAYOUT GENERALE ===
        HBox main = new HBox(20, grid, descrizione);
        main.setPadding(new Insets(15));

        VBox root = new VBox(10,
                new Label("Benvenuto su Isygate! Compila i campi per creare il tuo personaggio:"),
                main,
                btnCrea);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: #1b1b1b; -fx-text-fill: #e0e0c0;");

        Scene sc = new Scene(root, 750, 580);
        // 🔹 Applica il tema dark.css anche alla finestra di creazione PG
        var css = getClass().getResource("/dark.css");
        if (css != null) {
            sc.getStylesheets().add(css.toExternalForm());
        }
        dialog.setScene(sc);
        dialog.setResizable(false);
        dialog.centerOnScreen();
        dialog.show();
    }

    private Image safeLoad(String path, String fallback) {
        var url = getClass().getResource(path);
        if (url != null)
            return new Image(url.toExternalForm());
        // fallback
        var urlFb = getClass().getResource(fallback);
        if (urlFb != null)
            return new Image(urlFb.toExternalForm());
        // ultimo fallback: immagine vuota per non crashare
        return new Image(new java.io.ByteArrayInputStream(new byte[0]));
    }

    private Image imageForCycle(String sun, String moon) {
        String name;

        switch (sun) {
            case "alba":
                name = "sunrise.png";
                break;
            case "giorno":
                name = "noon.png";
                break;
            case "tramonto":
                name = "sunset.png";
                break;
            case "notte":
                // 🔹 durante la notte usiamo la fase della luna
                if (moon.contains("novilunio") || moon.contains("crescente")) {
                    name = "moonrise.png";
                } else if (moon.contains("pieno") || moon.contains("primo quarto")) {
                    name = "midnight.png";
                } else {
                    name = "moonset.png";
                }
                break;
            default:
                name = "noon.png"; // fallback
        }

        return safeLoad("/images/" + name, "/images/noon.png");
    }

    private Image imageForWeather(String w) {
        String name = switch (w) {
            case "sereno" -> "w_clear.png";
            case "variabile" -> "w_partly.png";
            case "nuvoloso" -> "w_cloudy.png"; // ✅ fix
            case "pioggia" -> "w_rain.png";
            case "temporale" -> "w_storm.png";
            case "neve" -> "w_snow.png";
            case "nebbia" -> "w_fog.png";
            default -> "w_clear.png";
        };
        return safeLoad("/images/" + name, "/images/w_clear.png");
    }

    // 👇 cambia modalità minimappa (iso o griglia)
    private void setMinimapMode(boolean iso) {
        this.isoMode = iso;
        // mostra un solo layer
        minimapGrid.setVisible(!isoMode);
        minimapPane.setVisible(isoMode);
    }

    private boolean isIsoMap(String mapName) {
        if (mapName == null)
            return false;
        // qui definisci quali mappe sono iso: città, dungeon, istanze...
        return !mapName.equalsIgnoreCase("overworld");
    }

    private String keyXY(int x, int y) {
        return x + "," + y;
    }

    // 🔹 nuova schermata Info
    private void showCharInfoUI(JsonObject r) {
        Stage dialog = new Stage();
        dialog.setTitle("Dettagli personaggio");

        VBox box = new VBox(10);
        box.setPadding(new Insets(15));

        String name = r.has("name") ? r.get("name").getAsString() : "?";
        String race = r.has("race") ? r.get("race").getAsString() : "?";
        String clazz = r.has("clazz") ? r.get("clazz").getAsString() : "?";

        Label lblHeader = new Label(name + " (" + race + " " + clazz + ")");
        lblHeader.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        box.getChildren().add(lblHeader);

        if (r.has("stats") && r.get("stats").isJsonObject()) {
            JsonObject stats = r.getAsJsonObject("stats");
            for (Map.Entry<String, JsonElement> e : stats.entrySet()) {
                Label l = new Label(e.getKey() + ": " + e.getValue().getAsString());
                box.getChildren().add(l);
            }
        }

        Scene scene = new Scene(box, 300, 400);
        dialog.setScene(scene);
        dialog.show();
    }

    // =============================================================================================
    // rendering testo con codici ANSI “soft”
    // =============================================================================================
    private void appendWithAnsiColors(String msg) {
        if (outputArea == null || msg == null)
            return;

        String currentStyle = "col-default";
        StringBuilder chunk = new StringBuilder();

        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);

            // === ANSI: \u001B[ ... m ===
            if (c == '\u001B' && i + 1 < msg.length() && msg.charAt(i + 1) == '[') {
                // flush del chunk prima di cambiare stile
                appendChunk(chunk.toString(), currentStyle);
                chunk.setLength(0);

                int end = msg.indexOf('m', i);
                if (end != -1) {
                    String code = msg.substring(i + 2, end);
                    switch (code) {
                        case "33":
                            currentStyle = "col-gold";
                            break;
                        case "31":
                            currentStyle = "col-red";
                            break;
                        case "32":
                            currentStyle = "col-lime";
                            break;
                        case "35":
                            currentStyle = "col-lilla";
                            break; // 💜 aggiunto per lilla
                        case "36":
                            currentStyle = "col-cyan";
                            break;
                        case "0":
                        case "39":
                        default:
                            currentStyle = "col-default";
                            break;
                    }
                    i = end; // salta sequenza ANSI
                    continue;
                }
            }

            // === Stile Minecraft: §x ===
            if (c == '§' && i + 1 < msg.length()) {
                appendChunk(chunk.toString(), currentStyle);
                chunk.setLength(0);

                char code = msg.charAt(++i);
                switch (code) {
                    case '0':
                        currentStyle = "col-black";
                        break;
                    case '1':
                        currentStyle = "col-darkblue";
                        break;
                    case '2':
                        currentStyle = "col-darkgreen";
                        break;
                    case '3':
                        currentStyle = "col-darkcyan";
                        break;
                    case '4':
                        currentStyle = "col-darkred";
                        break;
                    case '5':
                        currentStyle = "col-darkmagenta";
                        break;
                    case '6':
                        currentStyle = "col-gold";
                        break;
                    case '7':
                        currentStyle = "col-lightgray";
                        break;
                    case '8':
                        currentStyle = "col-darkgray";
                        break;
                    case '9':
                        currentStyle = "col-blue";
                        break;
                    case 'a':
                        currentStyle = "col-lime";
                        break;
                    case 'b':
                        currentStyle = "col-cyan";
                        break;
                    case 'c':
                        currentStyle = "col-red";
                        break;
                    case 'd':
                        currentStyle = "col-magenta";
                        break;
                    case 'e':
                        currentStyle = "col-yellow";
                        break;
                    case 'f':
                        currentStyle = "col-white";
                        break;
                    case 'r':
                        currentStyle = "col-default";
                        break; // reset
                    default:
                        currentStyle = "col-default";
                        break;
                }
                continue;
            }

            // accumula testo “normale”
            chunk.append(c);
        }

        // flush finale + newline
        appendChunk(chunk.toString(), currentStyle);
        appendChunk("\n", "col-default");

        // 🔁 Accumula anche nel buffer (solo se NON stiamo ripristinando dallo storico)
        if (!renderingFromBuffer && msg != null) {
            logBuf.append(msg).append('\n');
            // evita crescita infinita (conserva ~150KB)
            if (logBuf.length() > 200_000) {
                int keep = 150_000;
                logBuf.delete(0, logBuf.length() - keep);
            }
        }

        // 🔇 De-dup mirato per messaggi ripetitivi di connessione
        if (!renderingFromBuffer && msg != null) {
            if (msg.startsWith("§cConnessione interrotta.")
                    || msg.startsWith("§eAttendo che il server torni online")
                    || msg.startsWith("Bentornato su isygate!")) {

                if (msg.equals(lastStatusLine)) {
                    return; // skip duplicato immediato
                }
                lastStatusLine = msg;
            } else {
                lastStatusLine = null; // reset per altri messaggi
            }
        }

        // autoscroll
        outputArea.moveTo(outputArea.getLength());
        outputArea.requestFollowCaret();
    }

    // ============================================================================
    // Interpreta {ICON:xxx.gif} ma NON rompe i codici ANSI e non aggiunge newline
    // ============================================================================
    private void appendWithIcons(String msg) {
        if (outputArea == null || msg == null)
            return;

        // 🧹 evita riuso di icone vecchie (altrimenti compaiono in righe casuali)
        paragraphIconMap.clear();

        // spezza solo se ci sono newline espliciti nel messaggio
        String[] lines = msg.split("\\r?\\n", -1);

        for (String line : lines) {
            String iconFile = null;

            // 1️⃣ cerca tag {ICON:...} ma NON toccare le sequenze ANSI
            if (line.contains("{ICON:")) {
                iconFile = line.replaceAll(".*\\{ICON:([^}]+)\\}.*", "$1").trim();
                line = line.replaceAll("\\{ICON:[^}]+\\}\\s*", ""); // rimuovi solo il tag
            }

            // 2️⃣ scrivi la riga completa (con eventuali colori)
            int parIndex = Math.max(0, outputArea.getParagraphs().size() - 1);
            appendWithAnsiColors(line); // mantiene codici ANSI

            // 3️⃣ se aveva icona → caricala
            if (iconFile != null && !iconFile.isEmpty()) {
                try (InputStream in = getClass().getResourceAsStream("/maps/icons/" + iconFile)) {
                    InputStream src = in != null ? in : getClass().getResourceAsStream("/maps/icons/0.gif");
                    javafx.scene.image.Image img = new javafx.scene.image.Image(src, 0, 0, true, true);
                    javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(img);

                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.setFitHeight(18); // altezza base

                    // 🧩 HBox = icona + spazio "fisico"
                    javafx.scene.layout.HBox iconBox = new javafx.scene.layout.HBox();
                    iconBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    iconBox.getChildren().add(iv);

                    // spazio reale tra icona e testo
                    javafx.scene.text.Text space = new javafx.scene.text.Text(" ");
                    iconBox.getChildren().add(space);

                    paragraphIconMap.put(parIndex, iconBox);
                } catch (Exception ignored) {
                }
            }
        }

        // 🔹 ripulisce icone orfane dopo tutto il blocco (non ad ogni riga)
        paragraphIconMap.entrySet().removeIf(e -> {
            int idx = e.getKey();
            return idx >= outputArea.getParagraphs().size()
                    || outputArea.getParagraph(idx).getText().isBlank();
        });

        // niente newline manuali!
        outputArea.moveTo(outputArea.getLength());
        outputArea.requestFollowCaret();
    }

    /**
     * Mostra un messaggio "WOW" con effetto alert dorato o ciano.
     */
    private void appendWowAlert(String message, String color) {
        if (outputArea == null || message == null || message.isBlank())
            return;

        String colorCode;
        switch (color.toLowerCase()) {
            case "gold" -> colorCode = "§6";
            case "cyan" -> colorCode = "§b";
            case "red" -> colorCode = "§c";
            case "magenta" -> colorCode = "§d";
            case "lime" -> colorCode = "§a";
            default -> colorCode = "§e";
        }

        String border = colorCode + "═══════════════════════════════════════════════════════════════§r\n";
        String centered = colorCode + ">>> " + message + " <<<§r\n";

        appendWithAnsiColors(border);
        appendWithAnsiColors(centered);
        appendWithAnsiColors(border);

        // 💫 Effetto luminoso intorno al box del testo
        Platform.runLater(() -> {
            String glowColor;
            if (color.equalsIgnoreCase("gold"))
                glowColor = "rgba(255,215,0,0.7)";
            else if (color.equalsIgnoreCase("cyan"))
                glowColor = "rgba(0,255,255,0.7)";
            else if (color.equalsIgnoreCase("magenta"))
                glowColor = "rgba(255,0,255,0.7)";
            else
                glowColor = "rgba(255,255,180,0.6)";

            outputArea.setStyle(
                    "-fx-effect: dropshadow(gaussian, " + glowColor + ", 22, 0.6, 0, 0); " +
                            "-fx-background-color: #101010;");

            javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(
                    javafx.util.Duration.seconds(1.6));
            t.setOnFinished(e -> outputArea.setStyle("-fx-background-color: #101010;"));
            t.play();
        });
    }

    private void appendGlowEffect(String color) {
        Platform.runLater(() -> {
            String glowColor;
            if (color.equalsIgnoreCase("gold")) {
                glowColor = "rgba(255,215,0,0.8)";
            } else if (color.equalsIgnoreCase("cyan")) {
                glowColor = "rgba(0,255,255,0.8)";
            } else if (color.equalsIgnoreCase("red") || color.equalsIgnoreCase("attack")) {
                // 🔥 Effetto rosso speciale per attacco
                glowColor = "rgba(255,60,60,0.85)";
            } else {
                glowColor = "rgba(255,255,255,0.7)";
            }

            // 🔹 Usa DropShadow separato per non influire sul layout
            javafx.scene.effect.DropShadow glow = new javafx.scene.effect.DropShadow();
            glow.setColor(javafx.scene.paint.Color.web(glowColor));
            glow.setRadius(25);
            glow.setSpread(0.6);

            outputWrapper.setEffect(glow);

            // ✨ Piccolo flash animato solo sull’intensità della luce
            javafx.animation.Timeline pulse = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(0),
                            new javafx.animation.KeyValue(glow.radiusProperty(), 25)),
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(200),
                            new javafx.animation.KeyValue(glow.radiusProperty(), 35)),
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(400),
                            new javafx.animation.KeyValue(glow.radiusProperty(), 25)));
            pulse.setCycleCount(4);
            pulse.setOnFinished(e -> {
                FadeTransition ft = new FadeTransition(Duration.millis(250), outputWrapper);
                ft.setFromValue(1.0);
                ft.setToValue(0.95);
                ft.setAutoReverse(true);
                ft.setCycleCount(2);
                ft.setOnFinished(ev -> outputWrapper.setEffect(null));
                ft.play();
            });
            pulse.play();
        });
    }

    private void appendChunk(String text, String styleClass) {
        if (text == null || text.isEmpty())
            return;
        int start = outputArea.getLength();
        outputArea.appendText(text);
        outputArea.setStyleClass(start, start + text.length(), styleClass);
    }

    private static String toWeb(Color c) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    private void checkResources() {
        String[] files = {
                "/maps/icons/w121.gif", // pianura
                "/maps/icons/w122.gif", // bosco
                "/maps/icons/w031.gif", // colline
                "/maps/icons/w041.gif", // montagne
                "/maps/icons/940.gif", // città
                "/maps/icons/0.gif" // fallback
        };

        for (String f : files) {
            try (InputStream in = getClass().getResourceAsStream(f)) {
                // System.out.println("Risorsa " + f + " trovata? " + (in != null));
            } catch (Exception e) {
                System.out.println("Errore nel controllo risorsa " + f + ": " + e.getMessage());
            }
        }
    }

    private void updateMiniMap(JsonArray rows, int tileSize, int view, int cx, int cy, String playerIcon) {
        if (minimapGrid == null)
            return;

        minimapGrid.getChildren().clear();

        // dimensione tile con zoom → arrotondata per avere pixel interi
        final int scaledTile = (int) Math.round(tileSize * MINIMAP_ZOOM);

        // applica zoom anche a spaziatura e dimensione griglia
        minimapGrid.setHgap(0);
        minimapGrid.setVgap(0);
        minimapGrid.setPrefSize(view * scaledTile + (view - 1) * MINIMAP_ZOOM,
                view * scaledTile + (view - 1) * MINIMAP_ZOOM);

        final int rCount = rows.size();
        for (int y = 0; y < rCount; y++) {
            JsonArray row = rows.get(y).getAsJsonArray();
            for (int x = 0; x < row.size(); x++) {
                int tileId = row.get(x).getAsInt();

                // 🔹 Log di debug
                // System.out.println("Tile ricevuto alla cella (" + x + "," + y + "): " +
                // tileId);

                Image img = getTileImage(tileId);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(scaledTile);
                iv.setFitHeight(scaledTile);
                iv.setPreserveRatio(false);
                iv.setSmooth(true);

                StackPane cell = new StackPane(iv);
                cell.setMinSize(scaledTile, scaledTile);
                cell.setPrefSize(scaledTile, scaledTile);
                cell.setMaxSize(scaledTile, scaledTile);

                // bordo griglia scalato
                cell.setStyle("");

                // evidenzia il player al centro
                int center = view / 2;
                if (x == center && y == center) {
                    InputStream iconStream = getClass().getResourceAsStream("/maps/icons/" + playerIcon);

                    if (iconStream == null) {
                        System.out.println("[WARN] Icona " + playerIcon + " non trovata, uso default.gif");
                        iconStream = getClass().getResourceAsStream("/maps/icons/default.gif");
                    }

                    if (iconStream != null) {
                        Image playerImg = new Image(iconStream, scaledTile, scaledTile, false, true);
                        ImageView playerView = new ImageView(playerImg);
                        playerView.setFitWidth(scaledTile);
                        playerView.setFitHeight(scaledTile);
                        cell.getChildren().add(playerView);
                    } else {
                        // fallback definitivo → puntino
                        Label dot = new Label("●");
                        dot.setStyle(
                                "-fx-text-fill: white;" +
                                        "-fx-font-size: " + (8 * MINIMAP_ZOOM) + "px;" +
                                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.85), 8, 0.6, 0, 0);");
                        cell.getChildren().add(dot);
                    }
                }

                minimapGrid.add(cell, x, y);
            }
        }
    }

    private String resolveContext() {
        if (currentMapName == null)
            return "overworld";
        String n = currentMapName.toLowerCase();
        if (n.contains("dungeon"))
            return "dungeon";
        if (!n.equals("overworld"))
            return "city"; // tutto ciò che non è overworld
        return "overworld";
    }

    private boolean isCityLike() {
        return currentMapName != null && !currentMapName.equalsIgnoreCase("overworld");
    }

    private Image getTileImage(int tileId) {
        String ctx = resolveContext();
        String key = tileId + "@" + ctx;

        Image cached = tileCache.get(key);
        if (cached != null)
            return cached;

        String path = TileRegistry.getPath(tileId, ctx);
        if (path == null) {
            // fallback sicuro se non mappato nel manifest
            path = ctx.equals("overworld") ? "/maps/icons/0.gif" : "/maps/client/0Y.gif";
        }

        Image img = loadTileImage(path, tileId);
        tileCache.put(key, img);
        return img;
    }

    private Image loadTileImage(String path, int tileId) {
        InputStream in = getClass().getResourceAsStream(path);
        if (in == null) {
            System.err.println("[WARN] Tile " + tileId + " → path '" + path + "' NON trovato, uso /maps/icons/0.gif");
            in = getClass().getResourceAsStream("/maps/icons/0.gif");
        } else {
            System.out.println("[INFO] Caricata immagine per tile " + tileId + " → " + path);
        }

        Image img;
        if (in != null) {
            // ✅ Sempre dimensione nativa: la SCALA si decide solo nelle routine di disegno
            img = new Image(in);
        } else {
            System.err.println("[ERROR] Nessun fallback trovato per tile " + tileId + " → creo immagine vuota.");
            img = new Image(new java.io.ByteArrayInputStream(new byte[0]));
        }

        String ctx = resolveContext();
        String key = tileId + "@" + ctx;
        tileCache.put(key, img);
        return img;
    }

    // =============================================================================================
    // util UI
    // =============================================================================================
    private void alert(String s) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.WARNING, s, ButtonType.OK);
            a.showAndWait();
        });
    }

    private void showErrorOnUI(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            a.showAndWait();
        });
    }

    // =============================================================================================
    // Colori per statistiche
    // =============================================================================================
    private String getColorForStat(String stat) {
        switch (stat.toLowerCase()) {
            // === STATISTICHE PRINCIPALI ===
            case "forza":
                return "#ff3b30"; // rosso
            case "costituzione":
                return "#4cd964"; // verde
            case "volonta":
                return "#9b59b6"; // viola
            case "intelligenza":
                return "#3498db"; // blu
            case "destrezza":
                return "#f1c40f"; // giallo
            case "velocita":
                return "#e67e22"; // arancio
            case "empatia":
                return "#e84393"; // rosa
            case "taglia":
                return "#95a5a6"; // grigio neutro

            // === COLORI BASE USATI NELL’INTERFACCIA ===
            case "lilla":
                return "#a68bd0"; // lilla tenue (usato per le righe)
            case "bianco":
                return "#ffffff"; // testo standard
            case "giallo":
                return "#ffea00"; // evidenziazione e barra piena
            case "rosso":
                return "#ff4d4d"; // accenti o allerta
            case "verde":
                return "#4cd964"; // conferma/ok
            case "ciano":
                return "#3fa9f5"; // fallback azzurro chiaro
            case "arancio":
                return "#e67e22"; // toni caldi
            case "viola":
                return "#9b59b6"; // toni magici o mentali
            case "nero":
                return "#000000"; // sfondo
            case "grigio":
                return "#7f8c8d"; // testo secondario

            // === FALLBACK ===
            default:
                return "#3fa9f5"; // azzurro chiaro (default)
        }
    }

    // =============================================================================================
    // Utility stringa
    // =============================================================================================
    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // =============================================================================================
    // Normalizzazione direzioni (uguale al server)
    // =============================================================================================
    private String normalizeDir(String dir) {
        if (dir == null)
            return "";
        String d = dir.trim().toLowerCase();
        if (d.equals("n"))
            d = "nord";
        if (d.equals("s"))
            d = "sud";
        if (d.equals("e"))
            d = "est";
        if (d.equals("o") || d.equals("w"))
            d = "ovest";
        return d;
    }

    // ==========================================================
    // 🔁 Tentativo di riconnessione automatica
    // ==========================================================
    private boolean tryReconnect() {
        boolean reconnected = false;
        final int maxAttempts = 20;

        Platform.runLater(() -> appendWithAnsiColors("§eTentativo di riconnessione al server...§r\n"));

        try {
            Thread.sleep(2500);
        } catch (InterruptedException ignored) {
        }

        for (int attempt = 1; attempt <= maxAttempts && !reconnected; attempt++) {
            try {
                Thread.sleep(3500);
                final int currentAttempt = attempt;
                Platform.runLater(
                        () -> appendWithAnsiColors("§7Tentativo " + currentAttempt + "/" + maxAttempts + "...§r\n"));

                // chiudi residui
                try {
                    if (socket != null && !socket.isClosed())
                        socket.close();
                } catch (IOException ignored) {
                }
                socket = null;
                in = null;
                out = null;

                // serve il token, altrimenti torna a login
                if (sessionToken == null || sessionToken.isBlank()) {
                    Platform.runLater(() -> {
                        appendWithAnsiColors("§cToken assente o nullo. Torno al login.§r\n");
                        showLogin();
                    });
                    return false;
                }

                // apri connessione pulita
                connectToServer(currentHost, currentPort);
                if (socket == null || out == null || in == null) {
                    System.out.println("[Reconnect] Connessione non disponibile.");
                    continue;
                }

                // invia reauth
                JsonObject reauth = new JsonObject();
                reauth.addProperty("type", "reauth");
                reauth.addProperty("token", sessionToken);
                out.println(gson.toJson(reauth));

                // leggi fino a trovare 'auth' o timeout
                int oldTimeout = 0;
                try {
                    oldTimeout = socket.getSoTimeout();
                } catch (Exception ignored) {
                }
                try {
                    socket.setSoTimeout(6000);
                } catch (Exception ignored) {
                }

                long deadline = System.currentTimeMillis() + 6000;
                JsonObject authResp = null;

                while (System.currentTimeMillis() < deadline) {
                    String line = in.readLine();
                    if (line == null)
                        break;

                    JsonObject msg = gson.fromJson(line, JsonObject.class);
                    if (msg == null)
                        continue;

                    String type = msg.has("type") ? msg.get("type").getAsString() : "";

                    if ("auth".equals(type)) {
                        authResp = msg;
                        break;
                    } else {
                        // inoltra qualunque altro messaggio (es. "info" di benvenuto)
                        JsonObject forward = msg;
                        Platform.runLater(() -> handleServerMessage(forward));
                    }
                }

                try {
                    socket.setSoTimeout(oldTimeout);
                } catch (Exception ignored) {
                }

                if (authResp == null) {
                    System.out.println("[Reconnect] Nessuna risposta 'auth' ricevuta (timeout).");
                    continue;
                }

                // esito reauth
                if (authResp.has("reauth_failed") && authResp.get("reauth_failed").getAsBoolean()) {
                    Platform.runLater(() -> appendWithAnsiColors("§cToken non valido o scaduto. Accedi di nuovo.§r\n"));
                    showLogin();
                    return false;
                }

                if (authResp.has("ok") && authResp.get("ok").getAsBoolean()) {
                    // se il server ti ha dato un token nuovo, salvalo
                    if (authResp.has("token")) {
                        sessionToken = authResp.get("token").getAsString();
                    }
                    reconnected = true;

                    // 👇 separatore visivo dell’inizio nuova sessione
                    Platform.runLater(() -> appendWithAnsiColors("§7───────── §e[Riconnessione]§7 ─────────§r\n"));

                    // avvia il reader sulla nuova connessione
                    startReaderLoop();

                    // messaggio di esito
                    Platform.runLater(() -> appendWithAnsiColors("§a✅ Riconnessione completata.§r\n"));
                    break;
                }

                // risposta 'auth' ma non ok → riprova
                System.out.println("[Reconnect] 'auth' ricevuta ma non ok, riprovo.");

            } catch (IOException e) {
                System.out.println("[Reconnect] Tentativo " + attempt + " fallito: " + e.getMessage());
            } catch (Exception ex) {
                System.out.println("[Reconnect] Errore al tentativo " + attempt + ": " + ex.getMessage());
            }
        }

        if (!reconnected) {
            Platform.runLater(() -> {
                appendWithAnsiColors("§c❌ Server non raggiungibile dopo " + maxAttempts + " tentativi.§r\n");
                appendWithAnsiColors("§eTorno al login.§r\n");
                showLogin();
            });
            return false;
        }

        return true;
    }

    private void checkForUpdates(Label statusLabel) {
        new Thread(() -> {
            try {
                // URL della tua release "latest"
                URL url = new URL("https://api.github.com/repos/albertfre/isygate-client-dist/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "IsyGateClient");

                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null)
                        sb.append(line);
                    String json = sb.toString();

                    // Estrai il tag_name dalla risposta JSON
                    JsonObject obj = new Gson().fromJson(json, JsonObject.class);
                    String latestTag = obj.get("tag_name").getAsString();

                    Platform.runLater(() -> {
                        String current = getAppVersion();
                        if (!latestTag.equalsIgnoreCase("v" + current)) {
                            statusLabel.setText("🔄 Nuova versione disponibile: " + latestTag);
                            statusLabel.setStyle("-fx-text-fill: orange;");
                            statusLabel.setOnMouseClicked(e -> {
                                try {
                                    java.awt.Desktop.getDesktop().browse(new URI(obj.get("html_url").getAsString()));
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            });
                        } else {
                            statusLabel.setText("✔ IsyGateClient " + current + " è aggiornato.");
                            statusLabel.setStyle("-fx-text-fill: green;");
                        }
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("⚠ Impossibile verificare aggiornamenti");
                    statusLabel.setStyle("-fx-text-fill: gray;");
                });
            }
        }).start();
    }

    private String getAppVersion() {
        Package pkg = getClass().getPackage();
        String version = (pkg != null) ? pkg.getImplementationVersion() : null;
        return (version != null) ? version : "dev";
    }

    // =============================================================================================
    // shutdown
    // =============================================================================================
    @Override
    public void stop() throws Exception {
        closing = true; // 🔹 segnala chiusura
        try {
            if (socket != null)
                socket.close();
        } catch (Exception ignored) {
        }
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
