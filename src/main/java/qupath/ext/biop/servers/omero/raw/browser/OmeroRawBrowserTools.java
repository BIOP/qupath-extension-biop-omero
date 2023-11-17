package qupath.ext.biop.servers.omero.raw.browser;

import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.meta.GroupWrapper;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.PlateWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import fr.igred.omero.repository.ScreenWrapper;
import fr.igred.omero.repository.WellSampleWrapper;
import fr.igred.omero.repository.WellWrapper;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.ExperimenterData;
import omero.gateway.model.ImageData;
import omero.model.DatasetImageLink;
import omero.model.Experimenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class OmeroRawBrowserTools {
    final private static Logger logger = LoggerFactory.getLogger(OmeroRawBrowserTools.class);

    /**
     * Patterns to parse image URIs (for IDs)
     */
    private final static Pattern patternOldViewer = Pattern.compile("/webgateway/img_detail/(\\d+)");
    private final static Pattern patternNewViewer = Pattern.compile("images=(\\d+)");
    private final static Pattern patternWebViewer= Pattern.compile("/webclient/img_detail/(\\d+)");
    private final static Pattern patternLinkImage = Pattern.compile("show=image-(\\d+)");
    private final static Pattern patternImgDetail = Pattern.compile("img_detail/(\\d+)");
    private final static Pattern[] imagePatterns = new Pattern[] {patternOldViewer, patternNewViewer, patternWebViewer, patternImgDetail, patternLinkImage};

    /**
     * Pattern to recognize the OMERO type of an URI (i.e. Project, Dataset, Image, ..)
     */
    private final static Pattern patternType = Pattern.compile("show=(\\w+-)");

    /**
     * Patterns to parse Project and Dataset IDs ('link URI').
     */
    private final static Pattern patternLinkProject = Pattern.compile("show=project-(\\d+)");
    private final static Pattern patternLinkDataset = Pattern.compile("show=dataset-(\\d+)");

    /**
     * Get all the child OMERO objects present in the OMERO server with the specified parent.
     *
     * @param client
     * @param parent
     * @param group
     * @param owner
     * @return list of OmeroRawObjects
     */
    public static List<OmeroRawObjects.OmeroRawObject> readOmeroObjectsItems(OmeroRawObjects.OmeroRawObject parent, OmeroRawClient client,
                                                                             OmeroRawObjects.Group group, OmeroRawObjects.Owner owner) {
        if (parent == null)
            return new ArrayList<>();

        // get OMERO owner and group
        ExperimenterWrapper user = OmeroRawTools.getUser(client, owner.getId());
        GroupWrapper userGroup = OmeroRawTools.getGroup(client, group.getId());
        List<OmeroRawObjects.OmeroRawObject> list = new ArrayList<>();

        switch (parent.getType()){
            case SERVER:
                // get projects
                try {
                    list.addAll(getProjectItems(client,parent,user,userGroup));
                }catch(ServiceException | AccessException | ExecutionException e){
                    Dialogs.showErrorNotification("Reading projects", "Impossible to retrieve projects from your account");
                    logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
                }

                // get all screens
                try {
                    list.addAll(getScreenItems(client,parent,user,userGroup));
                }catch(ServiceException | AccessException | ExecutionException e){
                    Dialogs.showErrorNotification("Reading screens", "Impossible to retrieve screens from your account");
                    logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
                }

                // read orphaned dataset
                try {
                    list.addAll(getOrphanedDatasetItems(client,parent,user,userGroup));
                }catch(ServiceException | AccessException | ExecutionException | OMEROServerError e){
                    Dialogs.showErrorNotification("Reading orphaned dataset",
                            "Impossible to retrieve orphaned dataset from your account");
                    logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
                }
                return list;

            case PROJECT:
                try {
                    list.addAll(getDatasetItems(client, parent, user, userGroup));
                }catch(ServiceException | AccessException | ExecutionException e){
                    Dialogs.showErrorNotification("Reading datasets",
                            "Impossible to retrieve datasets from project '"+parent.getName()+"'");
                    logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
                }
                break;

            case DATASET:
                    list.addAll(getDatasetImageItems(parent, user, userGroup));
                break;

            case SCREEN:
                try{
                    list.addAll(getPlateItems(client, parent, user, userGroup));
                }catch(ServiceException | AccessException | ExecutionException e){
                    Dialogs.showErrorNotification("Reading Plates",
                            "Impossible to retrieve plates from screen '"+parent.getName()+"'");
                    logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
                }
                break;

            case PLATE:
                try {
                    list.addAll(getWellItems(client, parent, user, userGroup));
                }catch(ServiceException | AccessException | ExecutionException e){
                    Dialogs.showErrorNotification("Reading wells",
                            "Impossible to retrieve wells from plate '"+parent.getName()+"'");
                    logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
                }
                break;

            case WELL:
                list.addAll(getWellImageItems(parent, user, userGroup));
                break;
        }
        list.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));
        return list;
    }

    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} projects for the given user
     *
     * @param client
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getProjectItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject parent,
                                                                        ExperimenterWrapper user, GroupWrapper userGroup)
            throws AccessException, ServiceException, ExecutionException {
        List<OmeroRawObjects.OmeroRawObject> projectsList = new ArrayList<>();
        client.getSimpleClient().getProjects(user).forEach(projectWrapper -> {
            projectsList.add(new OmeroRawObjects.Project(projectWrapper, projectWrapper.getId(),
                    OmeroRawObjects.OmeroRawObjectType.PROJECT, parent, user, userGroup));
        });
        projectsList.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));
        return projectsList;
    }

    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} screens for the given user
     *
     * @param client
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getScreenItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject parent,
                                                                        ExperimenterWrapper user, GroupWrapper userGroup)
            throws AccessException, ServiceException, ExecutionException {
        List<OmeroRawObjects.OmeroRawObject> screensList = new ArrayList<>();
        client.getSimpleClient().getScreens(user).forEach(screenWrapper -> {
            screensList.add(new OmeroRawObjects.Screen(screenWrapper, screenWrapper.getId(), OmeroRawObjects.
                    OmeroRawObjectType.SCREEN, parent, user, userGroup));
        });
        screensList.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));
        return screensList;
    }

    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} orphaned datasets for the given user
     *
     * @param client
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getOrphanedDatasetItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject parent,
                                                                       ExperimenterWrapper user, GroupWrapper userGroup)
            throws AccessException, ServiceException, OMEROServerError, ExecutionException {
        List<OmeroRawObjects.OmeroRawObject> ophDatasetsList = new ArrayList<>();
        OmeroRawTools.readOmeroOrphanedDatasetsPerOwner(client, user).forEach(datasetWrapper -> {
            ophDatasetsList.add(new OmeroRawObjects.Dataset(datasetWrapper, datasetWrapper.getId(),
                    OmeroRawObjects.OmeroRawObjectType.DATASET, parent, user, userGroup));
        });
        ophDatasetsList.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getName));
        return ophDatasetsList;
    }

    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} datasets for a given project
     *
     * @param client
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getDatasetItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject parent,
                                                                 ExperimenterWrapper user, GroupWrapper userGroup)
            throws AccessException, ServiceException, ExecutionException {

        // get the current project to have access to the child datasets
        ProjectWrapper projectWrapper = (ProjectWrapper)parent.getWrapper();
        List<OmeroRawObjects.OmeroRawObject> datasetItems = new ArrayList<>();

        // if the current project has some datasets
        if(projectWrapper.asDataObject().asProject().sizeOfDatasetLinks() > 0){
            // get dataset ids
            Long[] linksId = projectWrapper.getDatasets().stream().map(DatasetWrapper::getId).toArray(Long[]::new);

            // get child datasets
            List<DatasetWrapper> datasets = client.getSimpleClient().getDatasets(linksId);

            // build dataset object
            for(DatasetWrapper dataset : datasets)
                datasetItems.add(new OmeroRawObjects.Dataset(dataset, dataset.getId(),
                        OmeroRawObjects.OmeroRawObjectType.DATASET, parent, user, userGroup));
        }
        return datasetItems;
    }


    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} images for a given dataset
     *
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getDatasetImageItems(OmeroRawObjects.OmeroRawObject parent,
                                                                             ExperimenterWrapper user, GroupWrapper userGroup){
        // get the current dataset to have access to the child images
        DatasetWrapper datasetWrapper = (DatasetWrapper)parent.getWrapper();
        List<OmeroRawObjects.OmeroRawObject> imageItems = new ArrayList<>();

        // if the current dataset has some images
        if(parent.getNChildren() > 0){
            List<DatasetImageLink> links = datasetWrapper.asDataObject().asDataset().copyImageLinks();

            // get child images
            for (DatasetImageLink link : links) {
                ImageWrapper imageWrapper = new ImageWrapper(new ImageData(link.getChild()));
                imageItems.add(new OmeroRawObjects.Image(imageWrapper ,imageWrapper.getId(),
                        OmeroRawObjects.OmeroRawObjectType.IMAGE, parent, user, userGroup));
            }
        }
        return imageItems;
    }

    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} images for a given well
     *
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getWellImageItems(OmeroRawObjects.OmeroRawObject parent,
                                                                             ExperimenterWrapper user, GroupWrapper userGroup){
        // get the current project to have access to the child datasets
        WellWrapper wellWrapper = (WellWrapper)parent.getWrapper();
        List<OmeroRawObjects.OmeroRawObject> imageItems = new ArrayList<>();

        // if the current well has some images
        if(parent.getNChildren() > 0) {
            // get child images
            for (WellSampleWrapper wSample : wellWrapper.getWellSamples()) {
                ImageWrapper image = wSample.getImage();
                imageItems.add(new OmeroRawObjects.Image(image, image.getId(),
                        OmeroRawObjects.OmeroRawObjectType.IMAGE, parent, user, userGroup));
            }
        }
        return imageItems;
    }


    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} plates for a given screen
     *
     * @param client
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getPlateItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject parent,
                                                                      ExperimenterWrapper user, GroupWrapper userGroup)
            throws AccessException, ServiceException, ExecutionException {
        // get the current screen to have access to the child plates
        ScreenWrapper screenWrapper = (ScreenWrapper)parent.getWrapper();
        List<OmeroRawObjects.OmeroRawObject> plateItems = new ArrayList<>();

        // if the current screen has some plates
        if(parent.getNChildren() > 0){
            List<Long> plateIds = screenWrapper.getPlates().stream().map(PlateWrapper::getId).collect(Collectors.toList());
            Collection<PlateWrapper> plates = OmeroRawTools.readPlates(client, plateIds);

            // build plate object
            for(PlateWrapper plate : plates)
                plateItems.add(new OmeroRawObjects.Plate(plate, plate.getId(),
                        OmeroRawObjects.OmeroRawObjectType.PLATE, parent, user, userGroup));
        }
        return plateItems;
    }

    /**
     * returns the list of {@link OmeroRawObjects.OmeroRawObject} wells for a given plate
     *
     * @param client
     * @param parent
     * @param user
     * @param userGroup
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    private static List<OmeroRawObjects.OmeroRawObject> getWellItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject parent,
                                ExperimenterWrapper user, GroupWrapper userGroup)
            throws AccessException, ServiceException, ExecutionException {
        // get the current project to have access to the child datasets
        PlateWrapper plateWrapper = (PlateWrapper)parent.getWrapper();
        List<OmeroRawObjects.OmeroRawObject> wellItems = new ArrayList<>();

        // get well for the current plate
        Collection<WellWrapper> wellDataList = plateWrapper.getWells(client.getSimpleClient());

        if(parent.getNChildren() > 0) {
            for(WellWrapper well : wellDataList)
                wellItems.add(new OmeroRawObjects.Well(well, well.getId(), 0,
                        OmeroRawObjects.OmeroRawObjectType.WELL, parent, user, userGroup));
        }
        return wellItems;
    }

    /**
     * Build an {@link OmeroRawObjects.Owner} object based on the OMERO {@link ExperimenterData} user
     *
     * @param user
     * @return
     */
    @Deprecated
    public static OmeroRawObjects.Owner getOwnerItem(Experimenter user){
        ExperimenterWrapper experimenterWrapper = new ExperimenterWrapper(new ExperimenterData(user));
        return new OmeroRawObjects.Owner(experimenterWrapper);
    }

    /**
     * Return the {@link OmeroRawObjects.Owner} object corresponding to the logged-in user on the current OMERO session
     *
     * @param client
     * @return
     */
    @Deprecated
    public static OmeroRawObjects.Owner getDefaultOwnerItem(OmeroRawClient client)  {
        return new OmeroRawObjects.Owner(client.getLoggedInUser());
    }


    /**
     * Return the group object corresponding to the default group attributed to the logged in user
     * @param client
     * @return
     */
    @Deprecated
    public static OmeroRawObjects.Group getDefaultGroupItem(OmeroRawClient client) {
        GroupWrapper userGroup = client.getLoggedInUser().getDefaultGroup();
        return new OmeroRawObjects.Group(userGroup.getId(), userGroup.getName());
    }

    /**
     * Return a map of available groups with its attached users.
     *
     * @param client
     * @return available groups for the current user
     */
    public static Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> getGroupUsersMapAvailableForCurrentUser(OmeroRawClient client) {
        // final map
        Map<OmeroRawObjects.Group,List<OmeroRawObjects.Owner>> map = new HashMap<>();

        // get all available groups for the current user according to his admin rights
        List<GroupWrapper> groups;
        if(client.isAdmin())
            groups = OmeroRawTools.getAllGroups(client);
        else
            groups = OmeroRawTools.getUserGroups(client, client.getLoggedInUser().getId());

        // remove "system" and "user" groups
        groups.stream()
                .filter(group->group.getId() != 0 && group.getId() != 1)
                .collect(Collectors.toList())
                .forEach(group-> {
                    // initialize lists
                    List<OmeroRawObjects.Owner> owners = new ArrayList<>();
                    OmeroRawObjects.Group userGroup = new OmeroRawObjects.Group(group.getId(), group.getName());

                    // get all available users for the current group
                    List<ExperimenterWrapper> users = OmeroRawTools.getGroupUsers(client, group.getId());

                    // convert each user to qupath compatible owners object
                    for (ExperimenterWrapper user : users)
                        owners.add(new OmeroRawObjects.Owner(user));

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
        Collection<ImageWrapper> orphanedImages = OmeroRawTools.readOmeroOrphanedImagesPerUser(client, owner.getWrapper());

        // get OMERO user and group
        ExperimenterWrapper user = OmeroRawTools.getUser(client, owner.getId());
        GroupWrapper userGroup = OmeroRawTools.getGroup(client, group.getId());

        // convert dataset to OmeroRawObject
        orphanedImages.forEach( e ->
            list.add(new OmeroRawObjects.Image(e, e.getId(), OmeroRawObjects.OmeroRawObjectType.IMAGE, new OmeroRawObjects.Server(client.getServerURI()), user, userGroup))
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
    @Deprecated
    public static OmeroRawAnnotations readAnnotationsItems(OmeroRawClient client, OmeroRawObjects.OmeroRawObject obj, OmeroRawAnnotations.OmeroRawAnnotationType category) {
        return OmeroRawAnnotations.getOmeroAnnotations(category, OmeroRawTools.readOmeroAnnotations(client, obj.getWrapper()));
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


    /**
     * Return a list of valid URIs from the given URI. If no valid URI can be parsed
     * from it, an IOException is thrown.
     * <p>
     * E.g. "{@code /host/webclient/?show=image=4|image=5}" returns a list containing:
     * "{@code /host/webclient/?show=image=4}" and "{@code /host/webclient/?show=image=5}".
     *
     * @param uri
     * @param client
     * @return list
     * @throws IOException
     */

    public static List<URI> getURIs(URI uri, OmeroRawClient client) throws IOException, DSOutOfServiceException, ExecutionException, DSAccessException {
        List<URI> list = new ArrayList<>();
        URI cleanServerUri = URI.create(uri.toString().replace("show%3Dimage-", "show=image-"));
        String elemId = "image-";
        String query = cleanServerUri.getQuery() != null ? uri.getQuery() : "";
        String shortPath = cleanServerUri.getPath() + query;

        Pattern[] similarPatterns = new Pattern[] {patternOldViewer, patternNewViewer, patternWebViewer};

        // Check for simpler patterns first
        for (int i = 0; i < similarPatterns.length; i++) {
            var matcher = similarPatterns[i].matcher(shortPath);
            if (matcher.find()) {
                elemId += matcher.group(1);

                try {
                    list.add(new URL(cleanServerUri.getScheme(), cleanServerUri.getHost(), cleanServerUri.getPort(), "/webclient/?show=" + elemId).toURI());
                } catch (MalformedURLException | URISyntaxException ex) {
                    logger.warn(ex.getLocalizedMessage());
                }
                return list;
            }
        }

        // If no simple pattern was matched, check for the last possible one: /webclient/?show=
        if (shortPath.startsWith("/webclient/show")) {
            URI newURI = getStandardURI(uri, client);
            var patternElem = Pattern.compile("image-(\\d+)");
            var matcherElem = patternElem.matcher(newURI.toString());
            while (matcherElem.find()) {
                list.add(URI.create(String.format("%s://%s%s%s%s%s",
                        uri.getScheme(),
                        uri.getHost(),
                        uri.getPort() >= 0 ? ":" + uri.getPort() : "",
                        uri.getPath(),
                        "?show=image-",
                        matcherElem.group(1))));
            }
            return list;
        }

        // At this point, no valid URI pattern was found
        throw new IOException("URI not recognized: " + uri);
    }

    public static URI getStandardURI(URI uri, OmeroRawClient client) throws IOException, ExecutionException, DSOutOfServiceException, DSAccessException {
        List<String> ids = new ArrayList<>();
        String vertBarSign = "%7C";

        // Identify the type of element shown (e.g. dataset)
        OmeroRawObjects.OmeroRawObjectType type;
        String query = uri.getQuery() != null ? uri.getQuery() : "";

        // Because of encoding, the equal sign might not be recognized when loading .qpproj file
        query = query.replace("%3D", "=");

        // Match
        var matcherType = patternType.matcher(query);
        if (matcherType.find())
            type = OmeroRawObjects.OmeroRawObjectType.fromString(matcherType.group(1).replace("-", ""));
        else
            throw new IOException("URI not recognized: " + uri);

        var patternId = Pattern.compile(type.toString().toLowerCase() + "-(\\d+)");
        var matcherId = patternId.matcher(query);
        while (matcherId.find()) {
            ids.add(matcherId.group(1));
        }

        // Cascading the types to get all ('leaf') images
        StringBuilder sb = new StringBuilder(
                String.format("%s://%s%s%s%s",
                        uri.getScheme(),
                        uri.getHost(),
                        uri.getPort() >= 0 ? ":" + uri.getPort() : "",
                        uri.getPath(),
                        "?show=image-"));

        List<String> tempIds = new ArrayList<>();
        // TODO: Support screen and plates
        switch (type) {
            case SCREEN:
            case PLATE:
            case WELL:
                break;
            case PROJECT:
                for (String id: ids) {
                    tempIds.add(client.getSimpleClient().getProject(Long.parseLong(id)).getDatasets()
                            .stream()
                            .map(DatasetWrapper::getId)
                            .toString());
                }
                ids =  new ArrayList<>(tempIds);
                tempIds.clear();
                type = OmeroRawObjects.OmeroRawObjectType.DATASET;

            case DATASET:
                for (String id: ids) {
                    tempIds.add(client.getSimpleClient().getDataset(Long.parseLong(id)).getImages(client.getSimpleClient())
                            .stream()
                            .map(ImageWrapper::getId)
                            .toString());
                }
                ids = new ArrayList<>(tempIds);
                tempIds.clear();
                type = OmeroRawObjects.OmeroRawObjectType.IMAGE;

            case IMAGE:
                if (ids.isEmpty())
                    logger.info("No image found in URI: " + uri);
                for (int i = 0; i < ids.size(); i++) {
                    String imgId = (i == ids.size()-1) ? ids.get(i) : ids.get(i) + vertBarSign + "image-";
                    sb.append(imgId);
                }
                break;
            default:
                throw new IOException("No image found in URI: " + uri);
        }

        return URI.create(sb.toString());
    }





    /**
     * Return the Id associated with the {@code URI} provided.
     * If multiple Ids are present, only the first one will be retrieved.
     * If no Id could be found, return -1.
     *
     * @param uri
     * @param type
     * @return Id
     */
    public static int parseOmeroRawObjectId(URI uri, OmeroRawObjects.OmeroRawObjectType type) {
        String cleanUri = uri.toString().replace("%3D", "=");
        Matcher m;
        switch (type) {
            case SERVER:
                logger.error("Cannot parse an ID from OMERO server.");
                break;
            case PROJECT:
                m = patternLinkProject.matcher(cleanUri);
                if (m.find()) return Integer.parseInt(m.group(1));
                break;
            case DATASET:
                m = patternLinkDataset.matcher(cleanUri);
                if (m.find()) return Integer.parseInt(m.group(1));
                break;
            case IMAGE:
                for (var p: imagePatterns) {
                    m = p.matcher(cleanUri);
                    if (m.find()) return Integer.parseInt(m.group(1));
                }
                break;
            default:
                throw new UnsupportedOperationException("Type (" + type + ") not supported");
        }
        return -1;
    }

    /**
     * Return the type associated with the {@code URI} provided.
     * If multiple types are present, only the first one will be retrieved.
     * If no type is found, return UNKNOWN.
     * <p>
     * Accepts the same formats as the {@code OmeroRawImageServer} constructor.
     * <br>
     * E.g., https://{server}/webclient/?show=dataset-{datasetId}
     *
     * @param uri
     * @return omeroRawObjectType
     */
    public static OmeroRawObjects.OmeroRawObjectType parseOmeroRawObjectType(URI uri) {
        var uriString = uri.toString().replace("%3D", "=");
        if (patternLinkProject.matcher(uriString).find())
            return OmeroRawObjects.OmeroRawObjectType.PROJECT;
        else if (patternLinkDataset.matcher(uriString).find())
            return OmeroRawObjects.OmeroRawObjectType.DATASET;
        else {
            for (var p: imagePatterns) {
                if (p.matcher(uriString).find())
                    return OmeroRawObjects.OmeroRawObjectType.IMAGE;
            }
        }
        return OmeroRawObjects.OmeroRawObjectType.UNKNOWN;
    }

}
