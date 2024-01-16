package qupath.ext.biop.servers.omero.raw.command;

import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawScripting;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;

public class OmeroRawWriteChannelSettingsCommand implements Runnable {
    private final String title = "Sending image & channels settings";
    private final QuPathGUI qupath;
    public OmeroRawWriteChannelSettingsCommand(QuPathGUI qupath)  {
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

        // build the GUI for import options
        GridPane pane = new GridPane();

        CheckBox cbImageName = new CheckBox("Image name");
        cbImageName.setSelected(false);

        CheckBox cbChannelNames = new CheckBox("Channel names");
        cbChannelNames.setSelected(false);

        CheckBox cbChannelDisplayRange = new CheckBox("Channel display ranges");
        cbChannelDisplayRange.setSelected(false);

        CheckBox cbChannelColor = new CheckBox("Channel colors");
        cbChannelColor.setSelected(false);

        int row = 0;
        pane.add(cbImageName, 0, row++);
        Label label = new Label("------ Only for fluorescence images ------");
        label.setAlignment(Pos.CENTER);
        pane.add(label, 0, row++, 2, 1);
        pane.add(cbChannelNames, 0, row++);
        pane.add(cbChannelDisplayRange, 0, row++);
        pane.add(cbChannelColor, 0, row);

        pane.setHgap(5);
        pane.setVgap(10);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean imageName = cbImageName.isSelected();
        boolean channelNames = cbChannelNames.isSelected();
        boolean channelDisplayRange = cbChannelDisplayRange.isSelected();
        boolean channelColor = cbChannelColor.isSelected();

        boolean wasSaved = true;

        // send display settings to OMERO
        if(imageName)
            wasSaved = OmeroRawScripting.sendQuPathImageNameToOmero((OmeroRawImageServer)imageServer, true);
        if(channelDisplayRange)
            wasSaved = OmeroRawScripting.sendQuPathChannelsDisplayRangeToOmero((OmeroRawImageServer)imageServer, true);
        if(channelColor)
            wasSaved = OmeroRawScripting.sendQuPathChannelsColorToOmero((OmeroRawImageServer)imageServer, true);
        if(channelNames)
            wasSaved = OmeroRawScripting.sendQuPathChannelsNameToOmero((OmeroRawImageServer)imageServer, true);

        if(wasSaved)
            Dialogs.showInfoNotification(" Image update successfully", "Image & channels settings have been successfully updated");
    }
}
