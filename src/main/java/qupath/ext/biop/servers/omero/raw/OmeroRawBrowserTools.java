package qupath.ext.biop.servers.omero.raw;

import omero.gateway.model.DatasetData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ScreenData;
import omero.gateway.model.WellData;
import omero.gateway.model.WellSampleData;
import omero.model.Experimenter;
import omero.model.ExperimenterGroup;
import omero.model.DatasetImageLink;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class OmeroRawBrowserTools {

    /**
     * Get all the child OMERO objects present in the OMERO server with the specified parent.
     *
     * @param client OmeroRawClient that handles the connection
     * @param parent parent OMERO container object
     * @param group current group from which the image is coming from
     * @param owner parent's owner
     * @return list of child OmeroRawObjects listed from the parent
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOmeroObjectsItems(OmeroRawObjects.OmeroRawObject parent, OmeroRawClient client,
                                                                             OmeroRawObjects.Group group, OmeroRawObjects.Owner owner) {
        if (parent == null)
            return new ArrayList<>();

        // get OMERO owner and group
        Experimenter user = OmeroRawTools.getOmeroUser(client, owner.getId(), owner.getName());
        ExperimenterGroup userGroup = OmeroRawTools.getOmeroGroup(client, group.getId(), group.getName());
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();

        switch (parent.getType()){
            case SERVER:
                // get all projects for the current user
                List<OmeroRawObjects.OmeroRawObject> projectsList = new ArrayList<>();
                Collection<ProjectData> projects = new ArrayList<>(OmeroRawTools.readOmeroProjectsByUser(client, owner.getId()));
                for(ProjectData project : projects)
                    projectsList.add(new OmeroRawObjects.Project("",project, project.getId(), OmeroRawObjects.OmeroRawObjectType.PROJECT, parent, user, userGroup));
                projectsList.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));

                // get all screens for the current user
                List<OmeroRawObjects.OmeroRawObject> screensList = new ArrayList<>();
                Collection<ScreenData> screens = new ArrayList<>(OmeroRawTools.readOmeroScreensByUser(client, owner.getId()));
                for(ScreenData screen : screens)
                    screensList.add(new OmeroRawObjects.Screen("",screen, screen.getId(), OmeroRawObjects.OmeroRawObjectType.SCREEN, parent, user, userGroup));
                screensList.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));

                // read orphaned dataset
                List<OmeroRawObjects.OmeroRawObject> ophDatasetsList = new ArrayList<>();
                Collection<DatasetData> orphanedDatasets = OmeroRawTools.readOmeroOrphanedDatasetsPerOwner(client, owner.getId());
                for(DatasetData ophDataset : orphanedDatasets)
                    ophDatasetsList.add(new OmeroRawObjects.Dataset("", ophDataset, ophDataset.getId(), OmeroRawObjects.OmeroRawObjectType.DATASET, parent, user, userGroup));
                ophDatasetsList.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));

                list.addAll(projectsList);
                list.addAll(ophDatasetsList);
                list.addAll(screensList);

                return list;

            case PROJECT:
                // get the current project to have access to the child datasets
                ProjectData projectData = (ProjectData)parent.getData();

                // if the current project has some datasets
                if(projectData.asProject().sizeOfDatasetLinks() > 0){
                    // get dataset ids
                    List<Long> linksId = projectData.getDatasets().stream().map(DatasetData::getId).collect(Collectors.toList());

                    // get child datasets
                    List<DatasetData> datasets = new ArrayList<>(OmeroRawTools.readOmeroDatasets(client,linksId));

                    // build dataset object
                    for(DatasetData dataset : datasets)
                        list.add(new OmeroRawObjects.Dataset("", dataset, dataset.getId(), OmeroRawObjects.OmeroRawObjectType.DATASET, parent, user, userGroup));
                }
                break;

            case DATASET:
                // get the current dataset to have access to the child images
                DatasetData datasetData = (DatasetData)parent.getData();

                // if the current dataset has some images
                if(parent.getNChildren() > 0){
                    List<ImageData> images = new ArrayList<>();
                    List<DatasetImageLink> links = datasetData.asDataset().copyImageLinks();

                    // get child images
                    for (DatasetImageLink link : links)
                        images.add(new ImageData(link.getChild()));

                    // build image object
                    for(ImageData image : images)
                        list.add(new OmeroRawObjects.Image("",image ,image.getId(), OmeroRawObjects.OmeroRawObjectType.IMAGE, parent, user, userGroup));
                }
                break;

            case SCREEN:
                // get the current screen to have access to the child plates
                ScreenData screenData = (ScreenData)parent.getData();

                // if the current screen has some plates
                if(parent.getNChildren() > 0){
                    List<Long> plateIds = screenData.getPlates().stream().map(PlateData::getId).collect(Collectors.toList());

                    Collection<PlateData> plates = OmeroRawTools.readOmeroPlates(client, plateIds);
                    // build plate object
                    for(PlateData plate : plates)
                        list.add(new OmeroRawObjects.Plate("", plate, plate.getId(), OmeroRawObjects.OmeroRawObjectType.PLATE, parent, user, userGroup));
                }
                break;

            case PLATE:
                // get the current project to have access to the child datasets
                PlateData plateData = (PlateData)parent.getData();
                // get well for the current plate
                Collection<WellData> wellDataList = OmeroRawTools.readOmeroWells(client, plateData.getId());

                if(parent.getNChildren() > 0) {
                    for(WellData well : wellDataList)
                        list.add(new OmeroRawObjects.Well("", well, well.getId(), 0, OmeroRawObjects.OmeroRawObjectType.WELL, parent, user, userGroup));
                }
                break;

            case WELL:
                // get the current project to have access to the child datasets
                WellData wellData = (WellData)parent.getData();

                // if the current well has some images
                if(parent.getNChildren() > 0) {
                    // get child images
                    for (WellSampleData wSample : wellData.getWellSamples()) {
                        ImageData image = wSample.getImage();
                        list.add(new OmeroRawObjects.Image("", image, image.getId(), OmeroRawObjects.OmeroRawObjectType.IMAGE, parent, user, userGroup));
                    }
                }
                break;
        }
        list.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));
        return list;
    }


    /**
     * @param user OMERO object
     * @return an {@link OmeroRawObjects.Owner} object based on the OMERO {@link Experimenter} user
     */
    public static OmeroRawObjects.Owner getOwnerItem(Experimenter user){
        return new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                user.getLastName()==null ? "" : user.getLastName().getValue(),
                user.getEmail()==null ? "" : user.getEmail().getValue(),
                user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                user.getOmeName()==null ? "" : user.getOmeName().getValue());
    }

    /**
     * Return the {@link OmeroRawObjects.Owner} object corresponding to the logged-in user on the current OMERO session
     *
     * @param client OmeroRawClient that handles the connection
     * @return
     */
    public static OmeroRawObjects.Owner getDefaultOwnerItem(OmeroRawClient client)  {
        return getOwnerItem(client.getLoggedInUser());
    }


    /**
     * Return the group object corresponding to the default group attributed to the logged in user
     * @param client OmeroRawClient that handles the connection
     * @return
     */
    public static OmeroRawObjects.Group getDefaultGroupItem(OmeroRawClient client) {
        ExperimenterGroup userGroup = OmeroRawTools.getDefaultOmeroGroup(client, client.getLoggedInUser().getId().getValue());
        return new OmeroRawObjects.Group(userGroup.getId().getValue(), userGroup.getName().getValue());
    }

    /**
     * Return a map of available groups with its attached users.
     *
     * @param client OmeroRawClient that handles the connection
     * @return available groups for the current user
     */
    public static Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> getGroupUsersMapAvailableForCurrentUser(OmeroRawClient client) {
        // final map
        Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> map = new HashMap<>();

        // get all available groups for the current user according to his admin rights
        List<ExperimenterGroup> groups;
        if(client.getIsAdmin())
            groups = OmeroRawTools.getAllOmeroGroups(client);
        else
            groups = OmeroRawTools.getUserOmeroGroups(client,client.getLoggedInUser().getId().getValue());

        // remove "system" and "user" groups
        groups.stream()
                .filter(group->group.getId().getValue() != 0 && group.getId().getValue() != 1)
                .toList()
                .forEach(group-> {
                    // initialize lists
                    List<OmeroRawObjects.Owner> owners = new ArrayList<>();
                    OmeroRawObjects.Group userGroup = new OmeroRawObjects.Group(group.getId().getValue(), group.getName().getValue());

                    // get all available users for the current group
                    List<Experimenter> users = OmeroRawTools.getOmeroUsersInGroup(client, group.getId().getValue());

                    // convert each user to qupath compatible owners object
                    for (Experimenter user : users)
                        owners.add(getOwnerItem(user));

                    // sort in alphabetic order
                    owners.sort(Comparator.comparing(OmeroRawObjects.Owner::getName));
                    map.put(userGroup, owners);
                });

        return new TreeMap<>(map);
    }


    /**
     * Get all the orphaned images from the server for a certain user as list of {@link OmeroRawObjects.OmeroRawObject}
     *
     * @param client
     * @param group
     * @param owner
     * @return
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOrphanedImagesItem(OmeroRawClient client, OmeroRawObjects.Group group, OmeroRawObjects.Owner owner){
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();

        // get orphaned datasets
        Collection<ImageData> orphanedImages = OmeroRawTools.readOmeroOrphanedImagesPerUser(client, owner.getId());

        // get OMERO user and group
        Experimenter user = OmeroRawTools.getOmeroUser(client, owner.getId(), owner.getName());
        ExperimenterGroup userGroup = OmeroRawTools.getOmeroGroup(client, group.getId(), group.getName());

        // convert dataset to OmeroRawObject
        orphanedImages.forEach( e ->
            list.add(new OmeroRawObjects.Image("", e, e.getId(), OmeroRawObjects.OmeroRawObjectType.IMAGE, new OmeroRawObjects.Server(client.getServerURI()),user, userGroup))
        );

        return list;
    }


    /**
     * Request the {@code OmeroRawAnnotations} object of type {@code category} associated with
     * the {@code OmeroRawObject} specified.
     *
     * @param client
     * @param obj
     * @param category
     * @return omeroRawAnnotations object
     */
    public static OmeroRawAnnotations readAnnotationsItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject obj, OmeroRawAnnotations.OmeroRawAnnotationType category) {
        return OmeroRawAnnotations.getOmeroAnnotations(client, category, OmeroRawTools.readOmeroAnnotations(client, obj.getData()));
    }

    /**
     * Adds the OMERO object hierarchy as QuPath metadata fields
     *
     * @param entry current QuPath entry
     * @param obj OMERO object to read the hierarchy from
     */
    public static void addContainersAsMetadataFields(ProjectImageEntry<BufferedImage> entry, OmeroRawObjects.OmeroRawObject obj){
        switch(obj.getType()){
            case SCREEN:
                entry.putMetadataValue("Screen",obj.getName());
                break;
            case PROJECT:
                entry.putMetadataValue("Project",obj.getName());
                break;
            case DATASET:
                entry.putMetadataValue("Dataset",obj.getName());
                addContainersAsMetadataFields(entry, obj.getParent());
                break;
            case PLATE:
                entry.putMetadataValue("Plate",obj.getName());
                addContainersAsMetadataFields(entry, obj.getParent());
                break;
            case WELL:
                entry.putMetadataValue("Well",obj.getName());
                addContainersAsMetadataFields(entry, obj.getParent());
                break;
            case IMAGE:
                addContainersAsMetadataFields(entry, obj.getParent());
            default:
                    break;
        }
    }

}
