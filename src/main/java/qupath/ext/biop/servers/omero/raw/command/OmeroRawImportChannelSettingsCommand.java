package qupath.ext.biop.servers.omero.raw.command;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.OmeroRawScripting;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;

/**
 * Import channel rendering settings from OMERO.
 * The channel's name, color and brightness & contrast can be separately retrieved
 *
 * @author Rémy Dornier
 *
 */
public class OmeroRawImportChannelSettingsCommand implements Runnable {
    private final String title = "Import channel settings from OMERO";
    private final QuPathGUI qupath;

    public OmeroRawImportChannelSettingsCommand(QuPathGUI qupath)  {
        this.qupath = qupath;
    }

    @Override
    public void run() {

        // get the current image
        ImageServer<BufferedImage> imageServer = this.qupath.getViewer().getServer();

        // Check if OMERO server
        if (!(imageServer instanceof OmeroRawImageServer)) {
            Dialogs.showErrorMessage(title, "The current image is not from OMERO!");
            return;
        }

        // build the GUI for view settings options
        GridPane pane = new GridPane();

        CheckBox cbChannelNames = new CheckBox("Channel names");
        cbChannelNames.setSelected(false);

        CheckBox cbChannelDisplayRange = new CheckBox("Channel display ranges");
        cbChannelDisplayRange.setSelected(false);

        CheckBox cbChannelColor = new CheckBox("Channel colors");
        cbChannelColor.setSelected(false);

        int row = 0;
        pane.add(new Label("Select channel settings options"), 0, row++, 2, 1);
        pane.add(cbChannelNames, 0, row++);
        pane.add(cbChannelDisplayRange, 0, row++);
        pane.add(cbChannelColor, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean channelNames = cbChannelNames.isSelected();
        boolean channelDisplayRange = cbChannelDisplayRange.isSelected();
        boolean channelColor = cbChannelColor.isSelected();

        // set OMERO display settings on QuPath image
        if(channelDisplayRange)
            OmeroRawScripting.setChannelsDisplayRangeFromOmeroChannel((OmeroRawImageServer)imageServer);
        if(channelColor)
            OmeroRawScripting.setChannelsColorFromOmeroChannel((OmeroRawImageServer)imageServer);
        if(channelNames)
            OmeroRawScripting.setChannelsNameFromOmeroChannel((OmeroRawImageServer)imageServer);

        Dialogs.showInfoNotification("Channel settings import","Channel settings successfully set the current image");
    }
}
