package com.athaydes.logfx;

import static com.athaydes.logfx.ui.Dialog.setPrimaryStage;
import static com.athaydes.logfx.ui.FontPicker.showFontPicker;
import static com.athaydes.logfx.ui.HighlightOptions.showHighlightOptionsDialog;

import com.athaydes.logfx.binding.BindableValue;
import com.athaydes.logfx.concurrency.TaskRunner;
import com.athaydes.logfx.config.Config;
import com.athaydes.logfx.config.Properties;
import com.athaydes.logfx.file.FileContentReader;
import com.athaydes.logfx.file.FileReader;
import com.athaydes.logfx.log.LogFXLogFactory;
import com.athaydes.logfx.ui.AboutLogFXView;
import com.athaydes.logfx.ui.Dialog;
import com.athaydes.logfx.ui.FileDragAndDrop;
import com.athaydes.logfx.ui.FileOpener;
import com.athaydes.logfx.ui.FxUtils;
import com.athaydes.logfx.ui.HighlightOptions;
import com.athaydes.logfx.ui.LogView;
import com.athaydes.logfx.ui.LogViewPane;
import com.athaydes.logfx.ui.MustCallOnJavaFXThread;
import com.athaydes.logfx.ui.StartUpView;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableSet;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The LogFX JavaFX Application.
 */
public class LogFX extends Application {

    // NOT static because it would cause initialization problems if it were
    private final Logger log = LoggerFactory.getLogger( LogFX.class );

    private static final String TITLE = "LogFX";

    private final BindableValue<Font> fontValue = new BindableValue<>(
            Font.font( FxUtils.isMac() ? "Monaco" : "Courier New" ) );

    private Stage stage;
    private final Pane root = new Pane();
    private final Rectangle overlay = new Rectangle( 0, 0 );
    private final Config config;
    private final HighlightOptions highlightOptions;
    private final LogViewPane logsPane;

    private final TaskRunner taskRunner = new TaskRunner( false );

    @MustCallOnJavaFXThread
    public LogFX() {
        Path configFile = Properties.LOGFX_DIR.resolve( "config" );
        this.config = new Config( configFile, taskRunner, fontValue );
        this.highlightOptions = new HighlightOptions(
                config.standardLogColorsProperty(),
                config.getObservableExpressions() );

        this.logsPane = new LogViewPane( taskRunner, () ->
                new StartUpView( getHostServices(), stage, this::open ),
                config.getObservableFiles().isEmpty() );

        logsPane.orientationProperty().bindBidirectional( config.panesOrientationProperty() );
    }

    @Override
    @MustCallOnJavaFXThread
    public void start( Stage primaryStage ) throws Exception {
        this.stage = primaryStage;
        setPrimaryStage( primaryStage );
        setIconsOn( primaryStage );

        MenuBar menuBar = new MenuBar();
        menuBar.useSystemMenuBarProperty().set( true );
        menuBar.getMenus().addAll( fileMenu(), viewMenu(), helpMenu() );

        VBox mainBox = new VBox( 10 );
        logsPane.prefHeightProperty().bind( mainBox.heightProperty() );
        mainBox.getChildren().addAll( menuBar, logsPane.getNode() );

        root.getChildren().addAll( mainBox, overlay );

        Scene scene = new Scene( root, 800, 600, Color.RED );

        root.prefHeightProperty().bind( scene.heightProperty() );
        root.prefWidthProperty().bind( scene.widthProperty() );

        mainBox.prefHeightProperty().bind( scene.heightProperty() );
        mainBox.prefWidthProperty().bind( scene.widthProperty() );

        primaryStage.setScene( scene );
        primaryStage.centerOnScreen();
        primaryStage.setTitle( TITLE );
        primaryStage.show();

        primaryStage.setOnHidden( event -> {
            logsPane.close();
            taskRunner.shutdown();
        } );

        openFilesFromConfig();
        
        Platform.runLater( () -> {
            log.debug( "Setting divider positions to {}", config.getPaneDividerPositions() );
            logsPane.setDividerPositions( config.getPaneDividerPositions() );
            logsPane.panesDividersProperty().addListener( observable ->
                    config.getPaneDividerPositions().setAll( logsPane.getSeparatorsPositions() ) );
        } );

        FxUtils.setupStylesheet( scene );
    }

    private void setIconsOn( Stage primaryStage ) {
        taskRunner.runAsync( () -> {
            final List<InputStream> imageStreams = Arrays.asList(
                    LogFX.class.getResourceAsStream( "/images/favicon-large.png" ),
                    LogFX.class.getResourceAsStream( "/images/favicon-small.png" ),
                    LogFX.class.getResourceAsStream( "/images/favicon-tiny.png" ) );

            Platform.runLater( () -> imageStreams.stream()
                    .map( Image::new )
                    .forEach( primaryStage.getIcons()::add ) );
        } );
    }

    @MustCallOnJavaFXThread
    private Menu fileMenu() {
        Menu menu = new Menu( "_File" );
        menu.setMnemonicParsing( true );

        MenuItem open = new MenuItem( "_Open File" );
        open.setAccelerator( new KeyCodeCombination( KeyCode.O, KeyCombination.SHORTCUT_DOWN ) );
        open.setMnemonicParsing( true );
        open.setOnAction( ( event ) -> new FileOpener( stage, this::open ) );

        MenuItem showLogFxLog = new MenuItem( "Open LogFX Log" );
        showLogFxLog.setAccelerator( new KeyCodeCombination( KeyCode.O,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN ) );
        showLogFxLog.setOnAction( ( event ) ->
                open( LogFXLogFactory.INSTANCE.getLogFilePath().toFile() ) );

        MenuItem close = new MenuItem( "E_xit" );
        close.setAccelerator( new KeyCodeCombination( KeyCode.W,
                KeyCombination.SHIFT_DOWN, KeyCombination.SHORTCUT_DOWN ) );
        close.setMnemonicParsing( true );
        close.setOnAction( ( event ) -> stage.close() );
        menu.getItems().addAll( open, showLogFxLog, close );

        return menu;
    }

    @MustCallOnJavaFXThread
    private Menu helpMenu() {
        Menu menu = new Menu( "_Help" );
        menu.setMnemonicParsing( true );

        MenuItem about = new MenuItem( "_About LogFX" );
        about.setOnAction( ( event ) -> new AboutLogFXView( getHostServices() ).show() );

        menu.getItems().addAll( about );

        return menu;
    }

    private void openFilesFromConfig() {
        List<String> cmdFiles = getParameters().getUnnamed();
        ObservableSet<File> observableFiles = config.getObservableFiles();
        if (cmdFiles.isEmpty()) {
            for ( File file : observableFiles) {
                Platform.runLater( () -> openViewFor( file, -1 ) );
            }
        } else {
            observableFiles.clear();
            for (String cmdFile : cmdFiles) {
                File file = new File(cmdFile);
                observableFiles.add(file);
                Platform.runLater( () -> openViewFor( file, -1 ) );
            }
        }
    }

    @MustCallOnJavaFXThread
    private void open( File file ) {
        open( file, -1 );
    }

    @MustCallOnJavaFXThread
    private void open( File file, int index ) {
        if ( config.getObservableFiles().contains( file ) ) {
            log.debug( "Tried to open file that is already opened, will focus on it" );
            logsPane.focusOn( file );
        } else {
            openViewFor( file, index );
            config.getObservableFiles().add( file );
        }
    }

    @MustCallOnJavaFXThread
    private void openViewFor( File file, int index ) {
        log.debug( "Creating file reader and view for file {}", file );

        FileContentReader fileReader = new FileReader( file, LogView.MAX_LINES );
        LogView view = new LogView( fontValue, root.widthProperty(),
                highlightOptions, fileReader, taskRunner );

        FileDragAndDrop.install( view, logsPane, overlay, ( droppedFile, target ) -> {
            int droppedOnPaneIndex = logsPane.indexOf( view );
            if ( droppedOnPaneIndex < 0 ) {
                open( droppedFile );
            } else {
                switch ( target ) {
                    case BEFORE:
                        open( droppedFile, droppedOnPaneIndex );
                        break;
                    case AFTER:
                        open( droppedFile, droppedOnPaneIndex + 1 );
                        break;
                    default:
                        throw new IllegalStateException( "Unknown target: " + target.name() );
                }
            }
        } );

        logsPane.add( view, () -> config.getObservableFiles().remove( file ), index );
    }

    @MustCallOnJavaFXThread
    private Menu viewMenu() {
        Menu menu = new Menu( "_View" );
        menu.setMnemonicParsing( true );

        CheckMenuItem highlight = new CheckMenuItem( "_Highlight Options" );
        highlight.setAccelerator( new KeyCodeCombination( KeyCode.H, KeyCombination.SHORTCUT_DOWN ) );
        highlight.setMnemonicParsing( true );
        bindMenuItemToDialog( highlight, () ->
                showHighlightOptionsDialog( highlightOptions ) );

        MenuItem orientation = new MenuItem( "Switch Pane Orientation" );
        orientation.setAccelerator( new KeyCodeCombination( KeyCode.S,
                KeyCombination.SHIFT_DOWN, KeyCombination.SHORTCUT_DOWN ) );
        orientation.setOnAction( event -> logsPane.switchOrientation() );

        CheckMenuItem font = new CheckMenuItem( "Fon_t" );
        font.setAccelerator( new KeyCodeCombination( KeyCode.F,
                KeyCombination.SHIFT_DOWN, KeyCombination.SHORTCUT_DOWN ) );
        font.setMnemonicParsing( true );
        bindMenuItemToDialog( font, () ->
                showFontPicker( fontValue.getValue(), fontValue::setValue ) );

        MenuItem showContextMenu = new MenuItem( "Show Context Menu" );
        showContextMenu.setAccelerator( new KeyCodeCombination( KeyCode.E, KeyCombination.SHORTCUT_DOWN ) );
        showContextMenu.setOnAction( event -> logsPane.showContextMenu() );

        menu.getItems().addAll( highlight, orientation, font, showContextMenu );
        return menu;
    }

    @MustCallOnJavaFXThread
    private static void bindMenuItemToDialog( CheckMenuItem menuItem, Callable<Dialog> dialogCreator ) {
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();

        menuItem.setOnAction( ( event ) -> {
            if ( menuItem.isSelected() ) {
                if ( dialogRef.get() == null || !dialogRef.get().isVisible() ) {
                    try {
                        Dialog dialog = dialogCreator.call();
                        dialogRef.set( dialog );
                        dialog.setOnHidden( e -> {
                            menuItem.setSelected( false );
                            dialogRef.set( null );
                        } );
                    } catch ( Exception e ) {
                        e.printStackTrace();
                    }
                }
            } else if ( dialogRef.get() != null ) {
                dialogRef.get().hide();
            }
        } );
    }

    public static void main( String[] args ) {
        if ( FxUtils.isMac() ) {
            SetupMacTrayIcon.run();
        }

        Font.loadFont( LogFX.class.getResource( "/fonts/fontawesome-webfont.ttf" ).toExternalForm(), 12 );
        Application.launch( LogFX.class, args );
    }

}
