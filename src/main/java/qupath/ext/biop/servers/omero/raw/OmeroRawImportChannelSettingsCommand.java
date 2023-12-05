package qupath.ext.biop.servers.omero.raw;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;

public class OmeroRawImportChannelSettingsCommand implements Runnable {

    private final String title = "Import channel settings from OMERO";
    private final QuPathGUI qupath;
    private final static Logger logger = LoggerFactory.getLogger(OmeroRawImportChannelSettingsCommand.class);
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

        Utils.infoLog(logger, "Channel settings import","Channel settings successfully set the current image", true);
    }

}
