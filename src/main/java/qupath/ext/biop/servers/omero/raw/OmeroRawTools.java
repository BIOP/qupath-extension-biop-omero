/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.biop.servers.omero.raw;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import fr.igred.omero.meta.ExperimenterWrapper;
import loci.formats.in.DefaultMetadataOptions;
import loci.formats.in.MetadataLevel;
import ome.formats.OMEROMetadataStoreClient;
import ome.formats.importer.ImportCandidates;
import ome.formats.importer.ImportConfig;
import ome.formats.importer.ImportContainer;
import ome.formats.importer.ImportLibrary;
import ome.formats.importer.OMEROWrapper;
import ome.formats.importer.cli.ErrorHandler;
import ome.formats.importer.cli.LoggingImportMonitor;
import omero.RLong;
import omero.ServerError;
import omero.api.IQueryPrx;
import omero.api.RenderingEnginePrx;
import omero.api.ThumbnailStorePrx;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;

import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.DataManagerFacility;
import omero.gateway.facility.MetadataFacility;
import omero.gateway.facility.ROIFacility;
import omero.gateway.facility.TablesFacility;

import omero.gateway.facility.TransferFacility;
import omero.gateway.model.AnnotationData;
import omero.gateway.model.ChannelData;
import omero.gateway.model.DataObject;
import omero.gateway.model.DatasetData;
import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.ImageData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.PixelsData;
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ROIData;
import omero.gateway.model.ROIResult;
import omero.gateway.model.ScreenData;
import omero.gateway.model.TableData;
import omero.gateway.model.TagAnnotationData;
import omero.gateway.model.WellData;
import omero.model.Dataset;
import omero.model.DatasetI;
import omero.model.Experimenter;
import omero.model.ExperimenterGroup;
import omero.model.IObject;
import omero.model.Image;
import omero.model.NamedValue;
import omero.model.Pixels;
import omero.model.PlateI;
import omero.model.ProjectDatasetLink;
import omero.model.ProjectDatasetLinkI;
import omero.model.ProjectI;
import omero.model.RenderingDef;
import omero.model.Shape;
import omero.model.TagAnnotation;
import omero.model.WellSample;
import omero.sys.ParametersI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javafx.scene.Node;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;

import javax.imageio.ImageIO;

import static omero.rtypes.rint;


/**
 * Static helper methods related to OMERORawImageServer.
 *
 * @author Melvin Gelbard
 *
 */
public final class OmeroRawTools {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawTools.class);

    /**
     * Patterns to parse image URIs (for IDs)
     */
    private final static Pattern patternOldViewer = Pattern.compile("/webgateway/img_detail/(\\d+)");
    private final static Pattern patternNewViewer = Pattern.compile("images=(\\d+)");
    private final static Pattern patternWebViewer= Pattern.compile("/webclient/img_detail/(\\d+)");
    private final static Pattern patternLinkImage = Pattern.compile("show=image-(\\d+)");
    private final static Pattern patternImgDetail = Pattern.compile("img_detail/(\\d+)");
    private final static String noImageThumbnail = "NoImage256.png";
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

    private final static String um = GeneralTools.micrometerSymbol();

    /**
     * Suppress default constructor for non-instantiability
     */
    private OmeroRawTools() {
        throw new AssertionError();
    }

    /**
     * Retrieve a user on OMERO based on its ID
     *
     * @param client
     * @param userId
     * @param username
     * @return The specified OMERO user
     */
    public static Experimenter getOmeroUser(OmeroRawClient client, long userId, String username){
        try {
            return client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).getExperimenter(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO user "+username +" ; id: "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve all users within the group on OMERO
     *
     * @param client
     * @param groupId
     * @return A list of all OMERO user within the specified group
     */
    public static List<ExperimenterWrapper> getOmeroUsersInGroup(OmeroRawClient client, long groupId){
        try {
            return client.getSimpleClient().getGroup(groupId).getExperimenters();
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO users in group "+groupId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve a group on OMERO based on its id
     *
     * @param client
     * @param groupId
     * @param groupName
     * @return The specified OMERO group
     */
    public static ExperimenterGroup getOmeroGroup(OmeroRawClient client, long groupId, String groupName){
        try {
            return client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).getGroup(groupId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO group "+groupName +" ; id: "+groupId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * get all the groups where the current user is member of
     *
     * @param client
     * @param userId
     * @return The list of user's OMERO groups
     */
    public static List<ExperimenterGroup> getUserOmeroGroups(OmeroRawClient client, long userId) {
        try {
            return client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).containedGroups(userId);
        }catch(DSOutOfServiceException | ServerError e){
            Dialogs.showErrorMessage("OMERO admin","Cannot retrieve OMERO groups for user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * get all the groups on OMERO server. This functionality is reserved to Admin people. In case you are not
     * Admin, {@link #getUserOmeroGroups(OmeroRawClient client, long userId)} method is called instead.
     *
     * @param client
     * @return The list of all groups on OMERO server
     */
    public static List<ExperimenterGroup> getAllOmeroGroups(OmeroRawClient client) {
        try {
            if(client.isAdmin())
                return client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).lookupGroups();
            else {
                Dialogs.showWarningNotification("OMERO admin", "You are not allowed to see all OMERO groups. Only available groups for you are loaded");
                return getUserOmeroGroups(client, client.getLoggedInUser().getId());
            }
        }catch(DSOutOfServiceException | ServerError e){
            Dialogs.showErrorMessage("OMERO admin", "Cannot retrieve all OMERO groups");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Get the default OMERO group of the specified user
     *
     * @param client
     * @param userId
     * @return User's OMERO default group
     */
    public static ExperimenterGroup getDefaultOmeroGroup(OmeroRawClient client, long userId) {
        try {
            return client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).getDefaultGroup(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read the default OMERO group for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve the group of which an image is part of.
     *
     * @param client
     * @param imageId
     * @return The group id
     */
    public static long getGroupIdFromImageId(OmeroRawClient client, long imageId){
        try {
            // request an imageData object for the image id by searching in all groups on OMERO
            // take care if the user is admin or not
            ImageData img = (ImageData) client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).findObject(client.getSimpleClient().getCtx(), "ImageData", imageId, true);
            return img.getGroupId();

        } catch (DSOutOfServiceException | NoSuchElementException | ExecutionException | DSAccessException e) {
            Dialogs.showErrorNotification("Get group id","Cannot retrieved group id from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return -1;
        }
    }


    /**
     * Get user's orphaned datasets from the OMERO server
     *
     * @param client the client {@link OmeroRawClient} object
     * @param userId
     * @return List orphaned of datasets
     */
    public static Collection<DatasetData> readOmeroOrphanedDatasetsPerOwner(OmeroRawClient client, long userId) {
        try {
            // query orphaned dataset
            List<IObject> datasetObjects = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAllByQuery("select dataset from Dataset as dataset " +
                    "join fetch dataset.details.owner as o " +
                    "where o.id = "+ userId +
                    "and not exists (select obl from " +
                    "ProjectDatasetLink as obl where obl.child = dataset.id) ", null);

            // get orphaned dataset ids
            List<Long> datasetIds = datasetObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .collect(Collectors.toList());

            // get orphaned datasets
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getDatasets(client.getSimpleClient().getCtx(), datasetIds);

        } catch (DSOutOfServiceException | ExecutionException | ServerError e) {
            Dialogs.showErrorMessage("Orphaned datasets","Cannot retrieved orphaned datasets for user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset of the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * Get all orphaned datasets from the OMERO server linked to the current group (contained in the security context of the current client).
     *
     * @param client
     * @return List of orphaned datasets
     */
    public static Collection<DatasetData> readOmeroOrphanedDatasets(OmeroRawClient client)  {
        Collection<DatasetData> orphanedDatasets;

        try {
            // query orphaned dataset
            List<IObject> datasetObjects = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAllByQuery("select dataset from Dataset as dataset " +
                            "left outer join fetch dataset.details.owner " +
                            "where not exists (select obl from " +
                            "ProjectDatasetLink as obl where obl.child = dataset.id) ", null);

            // get orphaned dataset ids
            List<Long> datasetIds = datasetObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .collect(Collectors.toList());

            // get orphaned datasets
            orphanedDatasets = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getDatasets(client.getSimpleClient().getCtx(), datasetIds);

        } catch (DSOutOfServiceException | ExecutionException | ServerError e) {
            Dialogs.showErrorMessage("Orphaned datasets","Cannot retrieved orphaned datasets");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        return orphanedDatasets;
    }


    /**
     * Get user's orphaned images from the OMERO server
     *
     * @param client
     * @param userId
     * @return List of orphaned images
     */
    public static Collection<ImageData> readOmeroOrphanedImagesPerUser(OmeroRawClient client, long userId) {
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getOrphanedImages(client.getSimpleClient().getCtx(), userId);
        } catch (ExecutionException e) {
            Dialogs.showErrorMessage("Orphaned images","Cannot retrieved orphaned images for user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Retrieve parents of OMERO containers (i.e. Image, Dataset, Well and Plate). For Project, Screen and other, it
     * returns an empty list.
     *
     * @param client
     * @param dataType image or container denomination (no case sensitive)
     * @param id image or container id
     * @return List of object's parent(s) or empty list
     */
    public static Collection<? extends DataObject> getParent(OmeroRawClient client, String dataType, long id){
        try{
            switch(dataType.toLowerCase()) {
                case "image":
                    // get the image
                    Image image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), id).asImage();

                    // get the parent datasets
                    List<IObject> datasetObjects = client.getSimpleClient().getGateway()
                            .getQueryService(client.getSimpleClient().getCtx())
                            .findAllByQuery("select link.parent from DatasetImageLink as link " +
                                    "where link.child=" + id, null);

                    if(!datasetObjects.isEmpty()) {
                        logger.info("The current image " + id + " has a dataset as parent");
                        // get projects' id
                        List<Long> ids = datasetObjects.stream()
                                .map(IObject::getId)
                                .map(RLong::getValue)
                                .distinct()
                                .collect(Collectors.toList());

                        return client.getSimpleClient().getGateway()
                                .getFacility(BrowseFacility.class)
                                .getDatasets(client.getSimpleClient().getCtx(), ids);
                    }else{
                        logger.info("The current image " + id + " has a well as parent");

                        List<IObject> wellSamplesObjects = client.getSimpleClient().getGateway()
                                .getQueryService(client.getSimpleClient().getCtx())
                                .findAllByQuery("select ws from WellSample ws where image=" + id, null);

                        List<Long> ids = wellSamplesObjects.stream()
                                .map(WellSample.class::cast)
                                .map(WellSample::getWell)
                                .map(IObject::getId)
                                .map(RLong::getValue)
                                .collect(Collectors.toList());

                        if(!ids.isEmpty())
                            return client.getSimpleClient().getGateway()
                                    .getFacility(BrowseFacility.class)
                                    .getWells(client.getSimpleClient().getCtx(), ids);
                        else {
                            Dialogs.showErrorNotification("Getting parent of image", "The current image " + id + " has no parent.");
                            break;
                        }
                    }

                case "dataset":
                    // get the parent projects
                    List<IObject> projectObjects = client.getSimpleClient().getGateway()
                            .getQueryService(client.getSimpleClient().getCtx())
                            .findAllByQuery("select link.parent from ProjectDatasetLink as link " +
                                    "where link.child=" + id, null);

                    // get projects' id
                    List<Long> projectIds = projectObjects.stream()
                            .map(IObject::getId)
                            .map(RLong::getValue)
                            .distinct()
                            .collect(Collectors.toList());

                    return client.getSimpleClient().getGateway()
                            .getFacility(BrowseFacility.class)
                            .getProjects(client.getSimpleClient().getCtx(), projectIds);

                case "well":
                    return Collections.singletonList(client.getSimpleClient().getGateway()
                                    .getFacility(BrowseFacility.class)
                                    .getWells(client.getSimpleClient().getCtx(), Collections.singletonList(id))
                                    .iterator()
                                    .next()
                                    .getPlate());

                case "plate":
                    // get parent screen
                    List<IObject> screenObjects = client.getSimpleClient().getGateway()
                            .getQueryService(client.getSimpleClient().getCtx())
                            .findAllByQuery("select link.parent from ScreenPlateLink as link " +
                                    "where link.child=" + id, null);

                    // get screens' id
                    List<Long> screenIds = screenObjects.stream()
                            .map(IObject::getId)
                            .map(RLong::getValue)
                            .distinct()
                            .collect(Collectors.toList());

                    return client.getSimpleClient().getGateway()
                            .getFacility(BrowseFacility.class)
                            .getScreens(client.getSimpleClient().getCtx(), screenIds);

                case "project":
                case "screen":
                    Dialogs.showWarningNotification("Getting parent","No parent for "+dataType+" id "+id);
                    break;
                default:
                    Dialogs.showWarningNotification("Getting parent","Unsupported object : "+dataType+" id "+id);
            }
        } catch (ServerError | DSOutOfServiceException | ExecutionException e) {
            Dialogs.showErrorNotification("Getting parent","Cannot retrieved the parent of "+dataType+" id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("Getting parent","You do not have access to "+dataType+" id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }

        return Collections.emptyList();
    }


    /**
     * Get the rendering settings object linked to the specified image.
     * Code partially copied from Pierre Pouchin from {simple-omero-client} project, {ImageWrapper} class, {getChannelColor} method
     *
     * @param client
     * @param imageId
     * @return Image's rendering settings object
     */
    public static RenderingDef readOmeroRenderingSettings(OmeroRawClient client, long imageId){
        try {
            // get pixel id
            long pixelsId = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId).getDefaultPixels().getId();
            // get rendering settings
            RenderingDef renderingDef = client.getSimpleClient().getGateway().getRenderingSettingsService(client.getSimpleClient().getCtx()).getRenderingSettings(pixelsId);

            if(renderingDef == null) {
                // load rendering settings if they were not automatically loaded
                RenderingEnginePrx re = client.getSimpleClient().getGateway().getRenderingService(client.getSimpleClient().getCtx(), pixelsId);
                re.lookupPixels(pixelsId);
                if (!(re.lookupRenderingDef(pixelsId))) {
                    re.resetDefaultSettings(true);
                    re.lookupRenderingDef(pixelsId);
                }
                re.load();
                re.close();
                return client.getSimpleClient().getGateway().getRenderingSettingsService(client.getSimpleClient().getCtx()).getRenderingSettings(pixelsId);
            }
            return renderingDef;

        } catch(ExecutionException | DSOutOfServiceException | ServerError | NullPointerException e){
            Dialogs.showErrorNotification("Rendering def reading","Could not read rendering settings on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        } catch(DSAccessException e){
            Dialogs.showErrorNotification("Rendering def reading","You don't have the right to access to the Rendering setting of the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * Get all OMERO projects corresponding to the list of ids
     *
     * @param client
     * @param projectIds
     * @return List of OMERO project objects
     */
    public static Collection<ProjectData> readOmeroProjects(OmeroRawClient client, List<Long> projectIds){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(), projectIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects","An error occurs when reading OMERO projects "+projectIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects","You don't have the right to access OMERO projects "+projectIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * Get all OMERO projects linked to the specified user.
     *
     * @param client
     * @param userId
     * @return User's list of OMERO project objects
     */
    public static Collection<ProjectData> readOmeroProjectsByUser(OmeroRawClient client, long userId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(), userId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects by user","An error occurs when reading OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects by user","You don't have the right to access OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO screens linked to the specified user.
     *
     * @param client
     * @param userId
     * @return User's list of OMERO project objects
     */
    public static Collection<ScreenData> readOmeroScreensByUser(OmeroRawClient client, long userId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getScreens(client.getSimpleClient().getCtx(), userId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects by user","An error occurs when reading OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects by user","You don't have the right to access OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get OMERO corresponding to the id
     *
     * @param client
     * @param datasetId
     * @return OMERO dataset or null object is ot doesn't exists
     */
    public static DatasetData readOmeroDataset(OmeroRawClient client, Long datasetId){
        Collection<DatasetData> datasets = readOmeroDatasets(client, Collections.singletonList(datasetId));
        if(datasets.isEmpty())
            return null;
        return datasets.iterator().next();

    }

    /**
     * Get all OMERO datasets corresponding to the list of ids
     *
     * @param client
     * @param datasetIds
     * @return List of OMERO dataset objects
     */
    public static Collection<DatasetData> readOmeroDatasets(OmeroRawClient client, List<Long> datasetIds){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getDatasets(client.getSimpleClient().getCtx(), datasetIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading datasets","An error occurs when reading OMERO datasets "+datasetIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading datasets","You don't have the right to access OMERO datasets "+datasetIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO datasets corresponding to the list of ids
     *
     * @param client
     * @param plateIds
     * @return List of OMERO dataset objects
     */
    public static Collection<PlateData> readOmeroPlates(OmeroRawClient client, List<Long> plateIds){

        String GET_PLATE_QUERY = "select p from Plate as p " +
                "left join fetch p.wells as w " +
                "left join fetch p.plateAcquisitions as pa " +
                "where p.id in (:ids)";
        try {
            IQueryPrx qs = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx());
            ParametersI param = new ParametersI();

            param.addIds(plateIds);
            return qs.findAllByQuery(GET_PLATE_QUERY, param).stream()
                    .map(PlateI.class::cast)
                    .map(PlateData::new)
                    .collect(Collectors.toList());

        }catch(DSOutOfServiceException | ServerError e){
            Dialogs.showErrorNotification("Reading plates","An error occurs when reading OMERO plates "+plateIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (NoSuchElementException e){
            Dialogs.showErrorNotification("Reading plates","You don't have the right to access OMERO plates "+plateIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO wells corresponding to the plate id
     *
     * @param client
     * @param plateId
     * @return List of OMERO dataset objects
     */
    public static Collection<WellData> readOmeroWells(OmeroRawClient client, long plateId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getWells(client.getSimpleClient().getCtx(), plateId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading datasets","An error occurs when reading wells in plate "+plateId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading wells","You don't have the right to access wells in plate "+plateId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * Get an image from OMERO server, corresponding to the specified id.
     *
     * @param client
     * @param imageId
     * @return OMERO image object
     */
    public static ImageData readOmeroImage(OmeroRawClient client, long imageId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading image","An error occurs when reading OMERO image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch (DSAccessException | NoSuchElementException e){
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * Get OMERO image's channels
     *
     * @param client
     * @param imageId
     * @return List of channels objects of the specified image
     */
    public static List<ChannelData> readOmeroChannels(OmeroRawClient client, long imageId){
        try {
            // get channels
            return client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getChannelData(client.getSimpleClient().getCtx(), imageId);
        } catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Channel reading","Could not read image channel on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Channel reading","You don't have the right to read channels on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * Get annotations (i.e. tag, key-value, comment...) attached to an image on OMERO, specified by its id.
     *
     * @param client
     * @param obj
     * @return List of annotation objects
     */
    public static List<AnnotationData> readOmeroAnnotations(OmeroRawClient client, DataObject obj){
        try {
             // read annotations linked to the image
            return client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), obj);

        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the object "+obj);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the right to read annotations on OMERO for the object "+obj);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * Get the image file format (ex. .lif, .vsi,...)
     *
     * @param client
     * @param imageId
     * @return Image file format
     */
    public static String readImageFileType(OmeroRawClient client, long imageId){
        try {
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);
            return imageData.asImage().getFormat().getValue().getValue();
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return "";
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the right to read annotations on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return "";
        }
    }

    /**
     * Get any Pojo object from OMERO
     * @param client
     * @param objectClassData The object class must implement DataObject class
     * @param id
     * @return
     */
    private static DataObject readObject(OmeroRawClient client, String objectClassData, long id){
        try {
            return  client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).findObject(client.getSimpleClient().getCtx(), objectClassData, id, true);
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO object", "Cannot get "+objectClassData+" from OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO object","You don't have the right to read "+objectClassData+" on OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return null;
    }


    /**
     * Get any Pojo object from OMERO
     * @param client
     * @param objectClass The object class must implement IObject class
     * @param id
     * @return
     */
    private static IObject readIObject(OmeroRawClient client, String objectClass, long id){
        try {
            return  client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).findIObject(client.getSimpleClient().getCtx(), objectClass, id, true);
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO object", "Cannot get "+objectClass+" from OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO object","You don't have the right to read "+objectClass+" on OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return null;
    }


    /**
     * Convert a QuPath measurement table to an OMERO table
     *
     * @param pathObjects
     * @param ob
     * @param client
     * @param imageId
     * @return The corresponding OMERO.Table
     */
    public static TableData convertMeasurementTableToOmeroTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, OmeroRawClient client, long imageId) {
        return UtilityTools.buildOmeroTableFromMeasurementTable(pathObjects, ob, client, imageId);
    }

    /**
     * Send an OMERO.table to OMERO server and attach it to the image specified by its ID.
     *
     * @param table OMERO.table
     * @param name table name
     * @param client
     * @param imageId
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addTableToOmero(TableData table, String name, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // attach the omero.table to the image
            client.getSimpleClient().getGateway().getFacility(TablesFacility.class).addTable(client.getSimpleClient().getCtx(), image, name, table);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Table Saving","Error during saving table on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Table Saving","You don't have the right to add a table on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Send an OMERO.table to OMERO server and attach it to the specified container
     *
     * @param table OMERO.table
     * @param name table name
     * @param client
     * @param container
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static TableData addTableToOmero(TableData table, String name, OmeroRawClient client, DataObject container) {
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(TablesFacility.class).addTable(client.getSimpleClient().getCtx(), container, name, table);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Table Saving","Error during saving table on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Table Saving","You don't have the right to add a table on OMERO for "
                    + container.getClass().getName()+" id " +container.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return null;
    }

    /**
     * Send an attachment to OMERO server and attached it to an image specified by its ID.
     *
     * @param file
     * @param client
     * @param imageId
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId) {
        return addAttachmentToOmero(file, client, imageId, null,"");
    }

    /**
     *  Send an attachment to OMERO server and attached it to an image specified by its ID.
     *  You can specify the mimetype of the file.
     *
     * @param file
     * @param client
     * @param imageId
     * @param miemtype
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype) {
        return addAttachmentToOmero(file, client, imageId, miemtype,"");
    }

    /**
     * Send an attachment to OMERO server and attached it to an image specified by its ID, specifying the mimetype and
     * a description of what the file is and how it works.
     *
     * @param file
     * @param client
     * @param imageId
     * @param miemtype
     * @param description
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype, String description) {
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // attach the omero.table to the image
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachFile(client.getSimpleClient().getCtx(), file, miemtype, description, file.getName(), image).get();

        } catch (ExecutionException | DSOutOfServiceException | InterruptedException e){
            Dialogs.showErrorNotification("File Saving","Error during saving file on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("File Saving","You don't have the right to save a file on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * Send an attachment to OMERO server and attached it in the specified container.
     *
     * @param file
     * @param client
     * @param obj
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj) {
        return addAttachmentToOmero(file, client, obj, null,"");
    }

    /**
     *  Send an attachment to OMERO server and attached it in the specified container.
     *  You can specify the mimetype of the file.
     *
     * @param file
     * @param client
     * @param obj
     * @param miemtype
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj, String miemtype) {
        return addAttachmentToOmero(file, client, obj, miemtype,"");
    }

    /**
     * Send an attachment to OMERO server and attached it in the specified container., specifying the mimetype and
     * a description of what the file is and how it works.
     *
     * @param file
     * @param client
     * @param obj
     * @param miemtype
     * @param description
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj, String miemtype, String description) {
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachFile(client.getSimpleClient().getCtx(), file, miemtype, description, file.getName(), obj).get();

        } catch (ExecutionException | InterruptedException e){
            Dialogs.showErrorNotification("File Saving","Error during saving file on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * Link an existing annotation of any type an OMERO object. The annotation should already
     *
     * @param client
     * @param annotationData
     * @param obj
     * @return
     */
    static AnnotationData linkAnnotationToOmero(OmeroRawClient client, AnnotationData annotationData, DataObject obj) {
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getSimpleClient().getCtx(), annotationData, obj);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Link Annotation","Error during linking the annotation on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("Link Annotation","You don't have the right to link objects on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * Update a list of OMERO objects
     *
     * @param client
     * @param objects
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateObjectsOnOmero(OmeroRawClient client, List<IObject> objects){
       boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).updateObjects(client.getSimpleClient().getCtx(), objects, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update objects","Error during updating objects on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update objects","You don't have the right to update objects on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * Update an OMERO object.
     *
     * @param client
     * @param object
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateObjectOnOmero(OmeroRawClient client, IObject object){
        boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).updateObject(client.getSimpleClient().getCtx(), object, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update object","Error during updating object on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update object","You don't have the right to update object on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * Update the thumbnail of an OMERO image, specified by its id, and given the ID of the updated RenderingDef object linked to that image.
     * <br> <br>
     * Be careful : the image should already have an OMERO ID.
     *
     * @param client
     * @param imageId
     * @param objectId
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateOmeroThumbnail(OmeroRawClient client, long imageId, long objectId){
        boolean wasAdded = true;

        // get the current image
        ImageData image = readOmeroImage(client, imageId);

        // get OMERO thumbnail store
        ThumbnailStorePrx store = null;
        try {
            store = client.getSimpleClient().getGateway().getThumbnailService(client.getSimpleClient().getCtx());
        } catch(DSOutOfServiceException e){
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
           return false;
        }

        if(store == null){
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Cannot get the Thumbnail service for image " + imageId);
            return false;
        }

        try {
            // get the pixel id to retrieve the correct thumbnail
            long pixelId = image.getDefaultPixels().getId();
            // get current thumbnail
            store.setPixelsId(pixelId);
            //set the new settings
            store.setRenderingDefId(objectId);

            try {
                // update the thumbnail
                store.createThumbnails();
            } catch (ServerError e) {
                logger.error("Error during thumbnail creation but thumbnail is updated ");
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
            }

        } catch (NullPointerException | ServerError e) {
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Thumbnail cannot be updated for image " + imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }

        try {
            // close the store
            store.close();
        } catch (ServerError e) {
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Cannot close the ThumbnailStore");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }

        return wasAdded;
    }

    /**
     * Download an image from OMERO in the path given in argument.
     *
     * @param client
     * @param imageId
     * @param path
     * @return Downloading status (True if downloaded ; false with error message otherwise)
     */
    public static boolean downloadImage(OmeroRawClient client, long imageId, String path){
        boolean wasDownloaded = true;
        try {
            if(new File(path).exists())
                client.getSimpleClient().getGateway().getFacility(TransferFacility.class).downloadImage(client.getSimpleClient().getCtx(), path, imageId);
            else {
                Dialogs.showErrorNotification("Download object","The following path does not exists : "+path);
                wasDownloaded = false;
            }
        } catch(DSOutOfServiceException | ExecutionException e){
            Dialogs.showErrorNotification("Download object","Error during downloading image "+imageId+" from OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasDownloaded = false;
        } catch(DSAccessException e){
            Dialogs.showErrorNotification("Download object","You don't have the right to download image "+imageId+" from OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasDownloaded = false;
        }

        return wasDownloaded;
    }

    /**
     * Upload an image to a specific dataset on OMERO
     *
     * @param client
     * @param datasetId
     * @param path
     * @return id of the newly uploaded image
     */
    public static List<Long> uploadImage(OmeroRawClient client, long datasetId, String path){
        Collection<DatasetData> datasets = readOmeroDatasets(client, Collections.singletonList(datasetId));
        if(!datasets.isEmpty())
            return uploadImage(client, datasets.iterator().next(), path);
        else {
            Dialogs.showErrorNotification("Upload image", "The dataset "+datasetId+" does not exist");
            return Collections.emptyList();
        }
    }


    /**
     * Upload an image to a specific dataset on OMERO
     * Code taken from simple-omero-client project from Pierre Pouchin (GreD-Clermont)
     *
     * @param client
     * @param dataset
     * @param path
     * @return id of the newly uploaded image
     */
    public static List<Long> uploadImage(OmeroRawClient client, DatasetData dataset, String path){
        if(dataset == null){
            Dialogs.showErrorNotification("Upload image", "The dataset you want to access does not exist");
            return Collections.emptyList();
        }

        ImportConfig config = new ImportConfig();
        config.target.set("Dataset:" + dataset.getId()); // can also import an image into a well or wellsample => to check
        config.username.set(client.getUsername());
        config.email.set(client.getLoggedInUser().getEmail());

        Collection<Pixels> pixels = new ArrayList<>(1);
        OMEROMetadataStoreClient store = null;
        try (OMEROWrapper reader = new OMEROWrapper(config)) {
            store = client.getSimpleClient().getGateway().getImportStore(client.getSimpleClient().getCtx());
            store.logVersionInfo(config.getIniVersionNumber());
            reader.setMetadataOptions(new DefaultMetadataOptions(MetadataLevel.ALL));

            ImportLibrary library = new ImportLibrary(store, reader);
            library.addObserver(new LoggingImportMonitor());

            ErrorHandler handler = new ErrorHandler(config);

            ImportCandidates candidates = new ImportCandidates(reader, new String[]{path}, handler);
            ExecutorService uploadThreadPool = Executors.newFixedThreadPool(config.parallelUpload.get());

            List<ImportContainer> containers = candidates.getContainers();
            if (containers != null) {
                for (int i = 0; i < containers.size(); i++) {
                    ImportContainer container = containers.get(i);
                    container.setTarget(dataset.asIObject());
                    List<Pixels> imported = library.importImage(container, uploadThreadPool, i);
                    pixels.addAll(imported);
                }
            }
            uploadThreadPool.shutdown();
        } catch (Throwable e) {
            Dialogs.showErrorNotification("Upload image","Error during uploading image "+path+" to OMERO.");
            logger.error(String.valueOf(e));
        } finally {
            if(store != null)
                store.logout();
        }

        List<Long> ids = new ArrayList<>(pixels.size());
        pixels.forEach(pix -> ids.add(pix.getImage().getId().getValue()));
        return ids.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Convert a QuPath measurement table into a CSV file,
     * including the OMERO image ID on which the measurements are referring to.
     *
     * @param pathObjects
     * @param ob
     * @param imageId
     * @param name file name
     * @param path where to save the newly created CSV file.
     * @return CSV file of measurement table.
     */
    public static File buildCSVFileFromMeasurementTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, long imageId, String name, String path) {
        return UtilityTools.buildCSVFileFromMeasurementTable(pathObjects, ob, imageId, name);
    }


    /**
     * Delete all existing ROIs on OMERO that are linked to an image, specified by its id.
     *
     * @param client
     * @param imageId
     */
    public static void deleteAllOmeroROIs(OmeroRawClient client, long imageId) {
        try {
            // extract ROIData
            List<IObject> roiData = readOmeroROIs(client, imageId).stream().map(ROIData::asIObject).collect(Collectors.toList());

            // delete ROis
            if(client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), roiData) == null)
                Dialogs.showInfoNotification("ROI deletion","No ROIs to delete of cannot delete them");

        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("ROI deletion","Could not delete existing ROIs on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("ROI deletion", "You don't have the right to delete ROIs on OMERO on the image  " + imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
    }

    /**
     * Delete the specified ROIs on OMERO that are linked to an image, specified by its id.
     *
     * @param client
     * @param roisToDelete
     */
    public static void deleteOmeroROIs(OmeroRawClient client, Collection<ROIData> roisToDelete) {
        try {
            // Convert to IObject
            List<IObject> roiData = roisToDelete.stream().map(ROIData::asIObject).collect(Collectors.toList());

            // delete ROis
            if(client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), roiData) == null)
                Dialogs.showInfoNotification("ROI deletion","No ROIs to delete");

        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("ROI deletion","Could not delete existing ROIs on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("ROI deletion", "You don't have the right to delete those ROIs on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
    }


    /**
     * Send ROIs to OMERO server and attached them to the specified image.
     *
     * @param client
     * @param imageId
     * @param omeroRois
     * @return Sending status (True if sent ; false with error message otherwise)
     */
    public static boolean writeOmeroROIs(OmeroRawClient client, long imageId, List<ROIData> omeroRois) {
        boolean roiSaved = false;

        // import ROIs on OMERO
        if (!(omeroRois.isEmpty())) {
            try {
                // save ROIs
                client.getSimpleClient().getGateway().getFacility(ROIFacility.class).saveROIs(client.getSimpleClient().getCtx(), imageId, client.getSimpleClient().getGateway().getLoggedInUser().getId(), omeroRois);
                roiSaved = true;
            } catch (ExecutionException | DSOutOfServiceException e){
                Dialogs.showErrorNotification("ROI Saving","Error during saving ROIs on OMERO.");
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
            } catch (DSAccessException e){
                Dialogs.showErrorNotification("ROI Saving","You don't have the right to write ROIs from OMERO on the image "+imageId);
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
            }
        } else {
            Dialogs.showInfoNotification("Upload annotations","There is no Annotations to upload on OMERO");
        }

        return roiSaved;
    }

    /**
     * Read ROIs from OMERO server attached to an image specified by its id.
     *
     * @param client
     * @param imageId
     * @return Image's list of OMERO ROIs
     */
    public static List<ROIData> readOmeroROIs(OmeroRawClient client, long imageId){
        List<ROIResult> roiList;

        // get ROIs from OMERO
        try {
            roiList = client.getSimpleClient().getGateway().getFacility(ROIFacility.class).loadROIs(client.getSimpleClient().getCtx(), imageId);
        } catch (DSOutOfServiceException | ExecutionException e) {
            Dialogs.showErrorNotification("ROI reading","Error during reading ROIs from OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return new ArrayList<>();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("ROI reading","You don't have the right to read ROIs from OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return new ArrayList<>();
         }

        if(roiList == null || roiList.isEmpty())
            return new ArrayList<>();

        // Convert them into ROIData
        List<ROIData> roiData = new ArrayList<>();
        for (ROIResult roiResult : roiList) {
            roiData.addAll(roiResult.getROIs());
        }

        return roiData;
    }

    /**
     * Convert QuPath pathObjects into OMERO ROIs.
     *
     * @param pathObjects
     * @return List of OMERO ROIs
     */
    public static List<ROIData> createOmeroROIsFromPathObjects(Collection<PathObject> pathObjects){
        return OmeroRawShapes.createOmeroROIsFromPathObjects(pathObjects);
    }

    /**
     * Convert OMERO ROIs into QuPath pathObjects
     *
     * @param roiData
     * @return List of QuPath pathObjects
     */
    public static Collection<PathObject> createPathObjectsFromOmeroROIs(List<ROIData> roiData){
        return OmeroRawShapes.createPathObjectsFromOmeroROIs(roiData);
    }

    /**
     * Read the comment attached to one shape of an OMERO ROI.
     *
     * @param shape
     * @return The shape comment
     */
    public static String getROIComment(Shape shape){
        return OmeroRawShapes.getROIComment(shape);
    }

    /**
     * Read the comments attach to an OMERO ROI (i.e. read each comment attached to each shape of the ROI)
     *
     * @param roiData
     * @return List of comments
     */
    public static List<String> getROIComment(ROIData roiData) {
        return OmeroRawShapes.getROIComment(roiData);
    }

    /**
     * Parse the comment based on the format introduced in {OmeroRawShapes.setRoiComment(PathObject src, String objectID, String parentID)}
     *
     * @param comment
     * @return The split comment
     */
    public static String[] parseROIComment(String comment) {
        return OmeroRawShapes.parseROIComment(comment);
    }


    /**
     * Splits the "target" map into two parts : one part containing key/values that are referenced in the "reference" map and
     * the other containing remaining key/values that are not referenced in the "reference".
     *
     * @param reference
     * @param target
     * @return List of new kvp and existing kvp maps
     */
    public static List<Map<String, String>> splitNewAndExistingKeyValues(Map<String, String> reference, Map<String, String> target){
        Map<String, String> existingKVP = new HashMap<>();

        // filter key/values that are contained in the reference
        reference.forEach((key, value) -> existingKVP.putAll(target.keySet()
                .stream()
                .filter(f -> f.equals(key))
                .collect(Collectors.toMap(e->key,e->target.get(key)))));

        // filter the new key values
        Map<String,String> updatedKV = target.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        existingKVP.forEach(updatedKV::remove);

        // add the two separate maps to a list.
        List<Map<String, String>> results = new ArrayList<>();
        results.add(existingKVP);
        results.add(updatedKV);

        return results;
    }


    /**
     * Get key-value pairs from OMERO server attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return List of Key-Value pairs as annotation objects
     */
    public static List<MapAnnotationData> readKeyValues(OmeroRawClient client, long imageId) {
        List<AnnotationData> annotations;

        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // read annotations linked to the image
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), imageData);

        }catch(ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("Reading OMERO key value pairs", "Cannot get key values from OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter key values
        return annotations.stream()
                .filter(MapAnnotationData.class::isInstance)
                .map(MapAnnotationData.class::cast)
                .collect(Collectors.toList());
    }


    /**
     * Get attachments from OMERO server attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return Sending status (True if retrieved ; false with error message otherwise)
     */
    public static List<FileAnnotationData> readAttachments(OmeroRawClient client, long imageId) {
        List<AnnotationData> annotations;
        try{
            // read image
            ImageData image = readOmeroImage(client, imageId);

            // get annotations
            List<Class<? extends AnnotationData>> types = Collections.singletonList(FileAnnotationData.class);
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), image, types, null);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading","Cannot read attachment from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading","You don't have the right to read attachments on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter attachments
        return annotations.stream()
                .filter(FileAnnotationData.class::isInstance)
                .map(FileAnnotationData.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Get attachments from OMERO server attached to the specified image.
     *
     * @param client
     * @param parent
     * @return Sending status (True if retrieved ; false with error message otherwise)
     */
    public static List<FileAnnotationData> readAttachments(OmeroRawClient client, DataObject parent) {
        List<AnnotationData> annotations;
        try{
            // get annotations
            List<Class<? extends AnnotationData>> types = Collections.singletonList(FileAnnotationData.class);
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), parent, types, null);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading",
                    "Cannot read attachment from "+parent.getClass().getName()+" id "+parent.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading",
                    "You don't have the right to read attachments on OMERO for "+parent.getClass().getName()+" id "+parent.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter attachments
        return annotations.stream()
                .filter(FileAnnotationData.class::isInstance)
                .map(FileAnnotationData.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Get tables from OMERO server attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return Sending status (True if retrieved ; false with error message otherwise)
     */
    public static Collection<FileAnnotationData> readTables(OmeroRawClient client, long imageId) {
        try{
            // read image
            ImageData image = readOmeroImage(client, imageId);

            // get annotations
            return client.getSimpleClient().getGateway().getFacility(TablesFacility.class).getAvailableTables(client.getSimpleClient().getCtx(), image);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading","Cannot read attachment from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading","You don't have the right to read attachments on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return Collections.emptyList();
    }

    /**
     * Delete given files on OMERO
     *
     * @param client
     * @param data
     */
    public static boolean deleteFiles(OmeroRawClient client, List<FileAnnotationData> data){
        boolean hasBeenDeleted = false;

        try{
            List<IObject> IObjectData = data.stream().map(FileAnnotationData::asIObject).collect(Collectors.toList());
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), IObjectData);
            hasBeenDeleted = true;
        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("File deletion","Could not delete files on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("File deletion", "You don't have the right to delete those files on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return hasBeenDeleted;
    }

    /**
     * create a new orphaned dataset on OMERO
     *
     * @param client
     * @param datasetName
     * @return OMERO dataset
     */
    public static DatasetData createNewDataset(OmeroRawClient client, String datasetName){
        return createNewDataset(client, datasetName, "");
    }

    /**
     * create a new dataset on OMERO and add a project as parent object
     *
     * @param client
     * @param projectId
     * @param datasetName
     * @return OMERO dataset
     */
    public static DatasetData createNewDataset(OmeroRawClient client, long projectId, String datasetName){
        return createNewDataset(client, projectId, datasetName, "");
    }

    /**
     * create a new orphaned dataset on OMERO
     *
     * @param client
     * @param datasetName
     * @param datasetDescription
     * @return OMERO dataset
     */
    public static DatasetData createNewDataset(OmeroRawClient client, String datasetName, String datasetDescription){
        // create a new dataset
        Dataset dataset = new DatasetI();
        dataset.setName(omero.rtypes.rstring(datasetName));
        dataset.setDescription(omero.rtypes.rstring(datasetDescription));

        try {
            // send the new dataset to OMERO
            IObject r = client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).saveAndReturnObject(client.getSimpleClient().getCtx(), dataset);
            return readOmeroDataset(client,r.getId().getValue());
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * create a new dataset on OMERO and add a project as parent object
     *
     * @param client
     * @param projectId
     * @param datasetName
     * @param datasetDescription
     * @return OMERO dataset
     */
    public static DatasetData createNewDataset(OmeroRawClient client, long projectId, String datasetName, String datasetDescription) {
        // create a new dataset
        Dataset dataset = new DatasetI();
        dataset.setName(omero.rtypes.rstring(datasetName));
        dataset.setDescription(omero.rtypes.rstring(datasetDescription));

        // link the dataset to a project
        ProjectDatasetLink link = new ProjectDatasetLinkI();
        link.setChild(dataset);
        link.setParent(new ProjectI(projectId, false));

        try {
            // send the new dataset to OMERO
            IObject r = client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).saveAndReturnObject(client.getSimpleClient().getCtx(), link);
            return readOmeroDataset(client,r.getId().getValue());
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName+" in the project "+projectId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO in the project "+projectId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * Read key value pairs from OMERO server and convert them into NamedValue OMERO-compatible-objects.
     *
     * @param client
     * @param imageId
     * @return List of NamedValue objects.
     */
    public static List<NamedValue> readKeyValuesAsNamedValue(OmeroRawClient client, long imageId) {
        return readKeyValues(client, imageId).stream()
                .flatMap(e->((List<NamedValue>)(e.getContent())).stream())
                .collect(Collectors.toList());
    }

    /**
     * Unlink tags from an image on OMERO
     *
     * @param client
     * @param imageId
     */
    protected static void unlinkTags(OmeroRawClient client, long imageId){
        try{
            List<TagAnnotationData> tags = readTags(client, imageId);
            List<IObject> oss = new ArrayList<>();
            for(TagAnnotationData tag : tags) {
                oss.addAll(client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAllByQuery("select link from ImageAnnotationLink" +
                        " link where link.parent = " + imageId +
                        " and link.child = " + tag.getId(), null));
            }
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), oss).block(500);

        }catch(ExecutionException | DSOutOfServiceException | ServerError | InterruptedException e) {
            Dialogs.showErrorNotification("Unlink tags", "Cannot unlink tags for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Unlink tags", "You don't have the right to unlink tags for image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
    }

    /**
     * Send key value pairs on OMERO and attach them to the specified image.
     *
     * @param keyValuePairs
     * @param client
     * @param imageId
     * @return Sending status (True if sent ; false with error message otherwise)
     */
    public static boolean addKeyValuesOnOmero(MapAnnotationData keyValuePairs, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // send key-values to OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getSimpleClient().getCtx(), keyValuePairs, imageData);

        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "Cannot add new key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to add some key value pairs on OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Update specified key value pairs on OMERO server.
     *
     * @param keyValuePairs
     * @param client
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateKeyValuesOnOmero(List<MapAnnotationData> keyValuePairs, OmeroRawClient client) {
        boolean wasUpdated = true;
        try {
            // update key-values to OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).updateObjects(client.getSimpleClient().getCtx(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()),null);
        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("OMERO KeyValues update", "Cannot update existing key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasUpdated = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to update key value pairs on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasUpdated = false;
        }
        return wasUpdated;
    }

    /**
     * Delete specified key value pairs on OMERO server
     *
     * @param keyValuePairs
     * @param client
     * @return Deleting status (True if deleted ; false with error message otherwise)
     */
    public static boolean deleteKeyValuesOnOmero(List<MapAnnotationData> keyValuePairs, OmeroRawClient client) {
        boolean wasDeleted = true;
        try {
            // remove current key-values
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()));
        } catch(ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("OMERO KeyValues deletion", "Cannot delete existing key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasDeleted = false;
        }
        return wasDeleted;
    }



    /**
     * Try to solve an error in OMERO regarding the keys creation.
     * On OMERO, it is possible to have two identical keys with a different value. This should normally never append.
     * This method checks if all keys are unique and output false if there is at least two identical keys.
     *
     * @param keyValues
     * @return Check status (True if all keys unique ; false otherwise)
     */
    public static boolean checkUniqueKeyInAnnotationMap(List<NamedValue> keyValues){ // not possible to have a map because it allows only unique keys
        boolean uniqueKey = true;

        for(int i = 0; i < keyValues.size()-1;i++){
            for(int j = i+1;j < keyValues.size();j++){
                if(keyValues.get(i).name.equals(keyValues.get(j).name)){
                    uniqueKey = false;
                    break;
                }
            }
            if(!uniqueKey)
                break;
        }
        return uniqueKey;
    }


    /**
     * Read tags from OMERO server, attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return List of Tag objects attached to the image
     */
    public static List<TagAnnotationData> readTags(OmeroRawClient client, long imageId) {
        List<AnnotationData> annotations;

        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // read annotations linked to the image
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO Tags", "Cannot get tags from OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Reading OMERO tags", "You don't have the right to read tags from OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter tags
        return annotations.stream()
                .filter(TagAnnotationData.class::isInstance)
                .map(TagAnnotationData.class::cast)
                .collect(Collectors.toList());
    }


    /**
     * Read all tags available for the logged-in user
     *
     * @param client
     * @return List of available tag objects
     */
    public static List<TagAnnotationData> readUserTags(OmeroRawClient client) {
        List<IObject> objects;

        try {
            // get current image from OMERO
            objects = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAll(TagAnnotation.class.getSimpleName(),null);
            
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO tags", "Error getting all available tags");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter tags
        return objects.stream()
                .map(TagAnnotation.class::cast)
                .map(TagAnnotationData::new)
                .collect(Collectors.toList());
    }

    /**
     *  Send a new tag on OMERO server and attach it to the specified image.
     *
     * @param tags
     * @param client
     * @param imageId
     * @return Sending status (True if sent ; false with error message otherwise)
     */
    public static boolean addTagsOnOmero(TagAnnotationData tags, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // send key-values to OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getSimpleClient().getCtx(), tags, imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "Cannot add new tags on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "You don't have the right to add tags on OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Get the thumbnail of the specified OMERO image.
     * <br> <br>
     * Code copied from Pierre Pouchin from {simple-omero-client} project, {ImageWrapper} class, {getThumbnail} method
     * and adapted for QuPath compatibility.
     *
     * @param client
     * @param imageId
     * @param prefSize
     * @return The image's thumbnail
     */
    public static BufferedImage getThumbnail(OmeroRawClient client, long imageId, int prefSize) {

        // get the current defaultPixel
        PixelsData pixel;
        try {
            pixel = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId).getDefaultPixels();
        }catch(ExecutionException | DSOutOfServiceException | DSAccessException | NullPointerException e){
            Dialogs.showErrorNotification( "Thumbnail reading","The thumbnail of image "+imageId+" cannot be read.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return readLocalImage(noImageThumbnail);
        }

        // set the thumbnail size
        int   sizeX  = pixel.getSizeX();
        int   sizeY  = pixel.getSizeY();
        float ratioX = (float) sizeX / prefSize;
        float ratioY = (float) sizeY / prefSize;
        float ratio  = Math.max(ratioX, ratioY);
        int   width  = (int) (sizeX / ratio);
        int   height = (int) (sizeY / ratio);

        // get rendering settings for the current image
        RenderingDef renderingSettings = readOmeroRenderingSettings(client, imageId);

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("Channel settings", "Cannot access to rendering settings of the image " + imageId);
            return readLocalImage(noImageThumbnail);
        }

        // get thumbnail
        byte[] array;
        ThumbnailStorePrx store = null;
        try {
            store = client.getSimpleClient().getGateway().getThumbnailService(client.getSimpleClient().getCtx());
            store.setPixelsId(pixel.getId());
            store.setRenderingDefId(renderingSettings.getId().getValue());
            array = store.getThumbnail(rint(width), rint(height));
        } catch (DSOutOfServiceException | ServerError | NullPointerException e) {
            Dialogs.showErrorNotification( "Thumbnail reading","The thumbnail of image "+imageId+" cannot be read.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return readLocalImage(noImageThumbnail);
        } finally {
            if(store != null){
                try{
                    store.close();
                } catch (ServerError s) {

                }
            }
        }

        // convert thumbnail into BufferedImage
        if (array != null) {
            try (ByteArrayInputStream stream = new ByteArrayInputStream(array)) {
                //Create a buffered image to display
                BufferedImage thumbnail = ImageIO.read(stream);
                if(thumbnail == null)
                    return readLocalImage(noImageThumbnail);
                else return thumbnail;
            }catch(IOException e){
                Dialogs.showErrorNotification( "Thumbnail reading","The thumbnail of image "+imageId+" cannot be converted to buffered image.");
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
                return readLocalImage(noImageThumbnail);
            }
        }
        else return readLocalImage(noImageThumbnail);
    }


    /**
     * read an image stored in the resource folder of the main class
     *
     * @param imageName
     * @return The read image or null if cannot be read
     */
    public static BufferedImage readLocalImage(String imageName){
        try {
            return ImageIO.read(OmeroRawTools.class.getClassLoader().getResource("images/"+imageName));
        }catch(IOException e){
            return new BufferedImage(256,256, BufferedImage.TYPE_BYTE_GRAY);
        }
    }


//	/**
//	 * Return a list of all {@code OmeroWebClient}s that are using the specified URI (based on their {@code host}).
//	 * @param uri
//	 * @return
//	 */
//	public static OmeroWebClient getWebClients(URI uri) {
//		var webclients = OmeroWebClients.getAllClients();
//		return webclients.values().parallelStream().flatMap(List::stream).filter(e -> e.getURI().getHost().equals(uri.getHost())).collect(Collectors.toList());
//	}


    /**
     * Return a clean URI of the server from which the given URI is specified. This method relies on
     * the specified {@code uri} to be formed properly (with at least a scheme and a host).
     * <p>
     * A few notes:
     * <ul>
     * <li> If the URI does not contain a host (but does a path), it will be returned without modification. </li>
     * <li> If no host <b>and</b> no path is found, {@code null} is returned. </li>
     * <li> If the specified {@code uri} does not contain a scheme, {@code https://} will be used. </li>
     * </ul>
     * <p>
     * E.g. {@code https://www.my-server.com/show=image-462} returns {@code https://www.my-server.com/}
     *
     * @param uri
     * @return clean uri
     */
    public static URI getServerURI(URI uri) {
        if (uri == null)
            return null;

        try {
            var host = uri.getHost();
            var path = uri.getPath();
            if (host == null || host.isEmpty())
                return (path == null || path.isEmpty()) ? null : uri;

            var scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty())
                scheme = "https://";
            return new URL(scheme, host, uri.getPort(), "").toURI();
        } catch (MalformedURLException | URISyntaxException ex) {
            logger.error("Could not parse server from {}: {}", uri, ex.getLocalizedMessage());
        }
        return null;
    }


    /**
     * OMERO requests that return a list of items are paginated
     * (see <a href="https://docs.openmicroscopy.org/omero/5.6.1/developers/json-api.html#pagination">OMERO API docs</a>).
     * Using this helper method ensures that all the requested data is retrieved.
     *
     * @param url
     * @return list of {@code Json Element}s
     * @throws IOException
     */
    // TODO: Consider using parallel/asynchronous requests
    static List<JsonElement> readPaginated(URL url) throws IOException {
        List<JsonElement> jsonList = new ArrayList<>();
        String symbol = (url.getQuery() != null && !url.getQuery().isEmpty()) ? "&" : "?";

        // Open connection
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        int response = connection.getResponseCode();

        // Catch bad response
        if (response != 200)
            return jsonList;

        JsonObject map;
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
            map = GsonTools.getInstance().fromJson(reader, JsonObject.class);
        }

        map.get("data").getAsJsonArray().forEach(jsonList::add);
        JsonObject meta = map.getAsJsonObject("meta");
        int offset = 0;
        int totalCount = meta.get("totalCount").getAsInt();
        int limit = meta.get("limit").getAsInt();
        while (offset + limit < totalCount) {
            offset += limit;
            URL nextURL = new URL(url + symbol + "offset=" + offset);
            InputStreamReader newPageReader = new InputStreamReader(nextURL.openStream());
            JsonObject newPageMap = GsonTools.getInstance().fromJson(newPageReader, JsonObject.class);
            newPageMap.get("data").getAsJsonArray().forEach(jsonList::add);
        }
        return jsonList;
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

    static List<URI> getURIs(URI uri, OmeroRawClient client) throws IOException, DSOutOfServiceException, ExecutionException, DSAccessException {
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

    static URI getStandardURI(URI uri, OmeroRawClient client) throws IOException, ExecutionException, DSOutOfServiceException, DSAccessException {
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
                    tempIds.add(client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(),Collections.singletonList(Long.parseLong(id))).iterator().next().getDatasets()
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

    static Node createStateNode(boolean loggedIn) {
        var state = loggedIn ? IconFactory.PathIcons.ACTIVE_SERVER : IconFactory.PathIcons.INACTIVE_SERVER;
        return IconFactory.createNode(QuPathGUI.TOOLBAR_ICON_SIZE, QuPathGUI.TOOLBAR_ICON_SIZE, state);
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

    public static String getErrorStackTraceAsString(Exception e){
        return Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).reduce("",(a, b)->a + "     at "+b+"\n");
    }

    /**
     * @return formatted date
     */
    public static String getCurrentDateAndHour(){
        LocalDateTime localDateTime = LocalDateTime.now();
        LocalTime localTime = localDateTime.toLocalTime();
        LocalDate localDate = localDateTime.toLocalDate();
        return String.valueOf(localDate.getYear())+
                (localDate.getMonthValue() < 10 ? "0"+localDate.getMonthValue():localDate.getMonthValue()) +
                (localDate.getDayOfMonth() < 10 ? "0"+localDate.getDayOfMonth():localDate.getDayOfMonth())+"-"+
                (localTime.getHour() < 10 ? "0"+localTime.getHour():localTime.getHour())+"h"+
                (localTime.getMinute() < 10 ? "0"+localTime.getMinute():localTime.getMinute())+"m"+
                (localTime.getSecond() < 10 ? "0"+localTime.getSecond():localTime.getSecond());

    }
}