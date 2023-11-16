package qupath.ext.biop.servers.omero.raw.browser;

import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.meta.GroupWrapper;
import omero.RLong;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.model.DatasetData;
import omero.gateway.model.ExperimenterData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ScreenData;
import omero.gateway.model.WellData;
import omero.gateway.model.WellSampleData;
import omero.model.Dataset;
import omero.model.DatasetImageLink;
import omero.model.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
     * Build an {@link OmeroRawObjects.Owner} object based on the OMERO {@link ExperimenterData} user
     *
     * @param user
     * @return
     */
    public static OmeroRawObjects.Owner getOwnerItem(ExperimenterWrapper user){
        //TODO see when the issue on Omerogateway will be solved and integrated
        String middleName = "";
        try{
            middleName = user.getMiddleName();
        }catch (Exception e){

        }
        return new OmeroRawObjects.Owner(user.getId(),
                user.getFirstName()==null ? "" : user.getFirstName(),
                middleName==null ? "" : middleName,
                user.getLastName()==null ? "" : user.getLastName(),
                user.getEmail()==null ? "" : user.getEmail(),
                user.getInstitution()==null ? "" : user.getInstitution(),
                user.getUserName()==null ? "" : user.getUserName());
    }

    /**
     * Return the {@link OmeroRawObjects.Owner} object corresponding to the logged-in user on the current OMERO session
     *
     * @param client
     * @return
     */
    public static OmeroRawObjects.Owner getDefaultOwnerItem(OmeroRawClient client)  {
        return getOwnerItem(client.getLoggedInUser());
    }


    /**
     * Return the group object corresponding to the default group attributed to the logged in user
     * @param client
     * @return
     */
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
        ExperimenterWrapper user = OmeroRawTools.getUser(client, owner.getId());
        GroupWrapper userGroup = OmeroRawTools.getGroup(client, group.getId());

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
                    tempIds.add(client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(), Collections.singletonList(Long.parseLong(id))).iterator().next().getDatasets()
                            .stream()
                            .map(DatasetData::asDataset)
                            .map(Dataset::getId)
                            .map(RLong::getValue)
                            .toString());
                }
                ids =  new ArrayList<>(tempIds);
                tempIds.clear();
                type = OmeroRawObjects.OmeroRawObjectType.DATASET;

            case DATASET:
                for (String id: ids) {
                    tempIds.add(client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImagesForDatasets(client.getSimpleClient().getCtx(),Collections.singletonList(Long.parseLong(id)))
                            .stream()
                            .map(ImageData::asImage)
                            .map(Image::getId)
                            .map(RLong::getValue)
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
