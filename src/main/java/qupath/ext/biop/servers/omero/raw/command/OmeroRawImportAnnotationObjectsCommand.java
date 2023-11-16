package qupath.ext.biop.servers.omero.raw.command;

import fr.igred.omero.meta.ExperimenterWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Import ROIs from OMERO for the current open image. ROIs can be filtered by owner to import only those from
 * a certain collaborator.
 * There is also an option to clean current annotations in QuPath.
 *
 * @author Rémy Dornier
 *
 */
public class OmeroRawImportAnnotationObjectsCommand implements Runnable{
    private final String title = "Import objects from OMERO";
    private final QuPathGUI qupath;
    private final double MAX_FONT_SIZE = 16.0;
    private final String ALL_USER_CHOICE = "All";

    public OmeroRawImportAnnotationObjectsCommand(QuPathGUI qupath)  {
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

        // get the list of available user for the current group (i.e. the one of the current image)
        OmeroRawImageServer omeroServer = ((OmeroRawImageServer) imageServer);
        long groupID = OmeroRawTools.getGroupIdFromImageId(omeroServer.getClient(), omeroServer.getId());
        List<String> userList = OmeroRawTools.getOmeroUsersInGroup(omeroServer.getClient(), groupID)
                .stream()
                .map(ExperimenterWrapper::getUserName)
                .collect(Collectors.toList());

        userList.add(0, ALL_USER_CHOICE);

        // build the GUI for import options
        GridPane generalPane = new GridPane();

        ComboBox<String> comboBox = new ComboBox<>(FXCollections.observableArrayList(userList));
        comboBox.getSelectionModel().selectFirst();

        CheckBox cbRemoveAnnotations = new CheckBox("Delete current QuPath annotations");
        cbRemoveAnnotations.setSelected(true);

        CheckBox cbRemoveDetections = new CheckBox("Delete current QuPath detections");
        cbRemoveDetections.setSelected(true);

        Label importOptLb = new Label("Select import options");
        importOptLb.setFont(new Font(MAX_FONT_SIZE));

        Label userLb = new Label("User");
        userLb.setTooltip(new Tooltip("Import ROIs created by a certain user"));

        GridPane userPane = new GridPane();
        userPane.add(userLb, 0, 0);
        userPane.add(comboBox,1,0);
        userPane.setHgap(5);
        userPane.setVgap(5);

        int row = 0;
        generalPane.add(importOptLb, 0, row++, 2, 1);
        generalPane.add(userPane,0, row++);
        generalPane.add(new Label("--------------------------------"), 0, row++);
        generalPane.add(cbRemoveAnnotations, 0, row++, 4, 1);
        generalPane.add(cbRemoveDetections, 0, row,4, 1);

        generalPane.setHgap(5);
        generalPane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, generalPane))
            return;

        // get user choice
        boolean removeDetections = cbRemoveDetections.isSelected();
        boolean removeAnnotations = cbRemoveAnnotations.isSelected();
        String user = comboBox.getSelectionModel().getSelectedItem();
        if(user.equals(ALL_USER_CHOICE))
            user = null;

        // read ROIs from OMERO
        Collection<PathObject> roiFromOmero;
        try {
            roiFromOmero = ((OmeroRawImageServer) imageServer).readPathObjects(user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // get the current hierarchy
        PathObjectHierarchy hierarchy = this.qupath.getViewer().getImageData().getHierarchy();

        // remove current annotations
        if(removeAnnotations)
            hierarchy.removeObjects(hierarchy.getAnnotationObjects(),true);

        // remove current detections
        if(removeDetections)
            hierarchy.removeObjects(hierarchy.getDetectionObjects(), false);

        // add rois from OMERO
        if(!roiFromOmero.isEmpty()) {
            hierarchy.addObjects(roiFromOmero);
            hierarchy.resolveHierarchy();
        }
        else{
            Dialogs.showWarningNotification(title, "The current image does not have any ROIs on OMERO");
        }
    }
}
