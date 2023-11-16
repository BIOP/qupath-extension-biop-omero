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

package qupath.ext.biop.servers.omero.raw.browser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import omero.gateway.model.DatasetData;
import omero.gateway.model.PlateAcquisitionData;
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PixelsData;
import omero.gateway.model.DataObject;
import omero.gateway.model.PermissionData;
import omero.gateway.model.ScreenData;
import omero.gateway.model.WellData;
import omero.model.ExperimenterGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ObservableList;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;


/**
 * Class regrouping all OMERO objects (most of which will be instantiated through deserialization) that represent
 * OMERO objects or data.
 *
 * @author Melvin Gelbard
 */
final class OmeroRawObjects {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawObjects.class);

    public enum OmeroRawObjectType {
        SERVER("#Server", "Server"),
        PROJECT("http://www.openmicroscopy.org/Schemas/OME/2016-06#Project", "Project"),
        DATASET("http://www.openmicroscopy.org/Schemas/OME/2016-06#Dataset", "Dataset"),
        IMAGE("http://www.openmicroscopy.org/Schemas/OME/2016-06#Image", "Image"),
        PLATE("https://www.openmicroscopy.org/Schemas/OME/2016-06/#Plate", "Plate"),
        WELL("https://www.openmicroscopy.org/Schemas/OME/2016-06/#Well", "Well"),
        SCREEN("https://www.openmicroscopy.org/Schemas/OME/2016-06/#Screen", "Screen"),
        PLATE_ACQUISITION("https://www.openmicroscopy.org/Schemas/OME/2016-06/#Plate-Aqcuisiton", "Plate acquisition"),

        // Object for OmeroRawBrowser's 'Orphaned folder' item (not for deserialization)
        ORPHANED_FOLDER("#OrphanedFolder", "Orphaned Folder"),

        // Default if unknown
        UNKNOWN("", "Unknown");

        private final String APIName;
        private final String displayedName;
        OmeroRawObjectType(String APIName, String displayedName) {
            this.APIName = APIName;
            this.displayedName = displayedName;
        }

        static OmeroRawObjectType fromString(String text) {
            for (var type : OmeroRawObjectType.values()) {
                if (type.APIName.equalsIgnoreCase(text) || type.displayedName.equalsIgnoreCase(text))
                    return type;
            }
            return UNKNOWN;
        }

        String toURLString() {
            return displayedName.toLowerCase() + 's';
        }

        @Override
        public String toString() {
            return displayedName;
        }
    }

    static abstract class OmeroRawObject {

        private long id = -1;
        protected String name;
        protected String type;
        private Owner owner;
        private Group group;
        private OmeroRawObject parent;

        private DataObject data;

        /**
         * Return the OMERO ID associated with this object.
         * @return id
         */
        long getId() {
            return id;
        }

        /**
         * Set the id of this object
         * @param id
         */
        void setId(long id) {
            this.id = id;
        }

        /**
         * Return the OMERO data associated with this object.
         * @return id
         */
        DataObject getData() {
            return data;
        }

        /**
         * Set the data of this object
         * @param obj
         */
        void setData(DataObject obj) {
            this.data = obj;
        }

        /**
         * Return the name associated with this object.
         * @return name
         */
        String getName() {
            return name;
        }

        /**
         * Set the name of this object
         * @param name
         */
        void setName(String name) {
            this.name = name;
        }


        /**
         * Return the URL associated with this object.
         * @return url
         */
        abstract String getAPIURLString();

        /**
         * Return the {@code OmeroRawObjectType} associated with this object.
         * @return type
         */
        OmeroRawObjectType getType() {
            return OmeroRawObjectType.fromString(type);
        }

        /**
         * Set the type of this object
         * @param type
         */
        void setType(String type) {
            this.type = OmeroRawObjectType.fromString(type).toString();
        }

        /**
         * Return the OMERO owner of this object
         * @return owner
         */
        Owner getOwner() {
            return owner;
        }

        /**
         * Set the owner of this OMERO object
         * @param owner
         */
        void setOwner(Owner owner) {
            this.owner = owner;
        }

        /**
         * Return the OMERO group of this object
         * @return group
         */
        Group getGroup() {
            return group;
        }

        /**
         * Set the group of this OMERO object
         * @param group
         */
        void setGroup(Group group) {
            this.group = group;
        }

        /**
         * Return the parent of this object
         * @return parent
         */
        OmeroRawObject getParent() {
            return parent;
        }

        /**
         * Set the parent of this OMERO object
         * @param parent
         */
        void setParent(OmeroRawObject parent) {
            this.parent = parent;
        }

        /**
         * Return the number of children associated with this object
         * @return nChildren
         */
        int getNChildren() {
            return 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;

            if (!(obj instanceof OmeroRawObject))
                return false;

            return id == ((OmeroRawObject)obj).getId();
        }
    }

    static class Server extends OmeroRawObject {

        private final String url;

        public Server(URI uri) {
            super.id = -1;
            super.type = "Server";
            super.owner = null;
            super.name = "";
            super.group = null;
            super.parent = null;
            this.url = uri.toString();
        }

        @Override
        String getAPIURLString() {
            return url;
        }
    }


    /**
     * The {@code Orphaned folder} class differs from other in this class as it
     * is never created through deserialization of JSON objects. Note that it should only
     * contain orphaned images, <b>not</b> orphaned datasets (like the OMERO webclient).
     * <p>
     * It should only be used once per {@code OmeroRawImageServerBrowser}, with its children objects loaded
     * in an executor (see {@link OmeroRawTools#readOmeroOrphanedImagesPerUser}). This class keeps track of:
     * <li>Total child count: total amount of orphaned images on the server.</li>
     * <li>Current child count: what is displayed in the current {@code OmeroRawServerImageBrowser}, which depends on what is loaded and the current Group/Owner.</li>
     * <li>Child count: total amount of orphaned images currently loaded (always smaller than total child count).</li>
     * <li>{@code isLoading} property: defines whether QuPath is still loading its children objects.</li>
     * <li>List of orphaned image objects.</li>
     */
    static class OrphanedFolder extends OmeroRawObject {

        /**
         * Number of children currently to display (based on Group/Owner and loaded objects)
         */
        private final IntegerProperty currentChildCount;

        /**
         * Number of children objects loaded
         */
        private final AtomicInteger loadedChildCount;

        /**
         * Total number of children (loaded + unloaded)
         */
        private final AtomicInteger totalChildCount;

        private final BooleanProperty isLoading;
        private final ObservableList<OmeroRawObject> orphanedImageList;

        public OrphanedFolder(ObservableList<OmeroRawObject> orphanedImageList) {
            this.name = "Orphaned Images";
            this.type = OmeroRawObjectType.ORPHANED_FOLDER.toString();
            this.currentChildCount = new SimpleIntegerProperty(0);
            this.loadedChildCount = new AtomicInteger(0);
            this.totalChildCount = new AtomicInteger(-1);
            this.isLoading = new SimpleBooleanProperty(true);
            this.orphanedImageList = orphanedImageList;
        }

        IntegerProperty getCurrentCountProperty() {
            return currentChildCount;
        }

        int incrementAndGetLoadedCount() {
            return loadedChildCount.incrementAndGet();
        }

        void setTotalChildCount(int newValue) {
            totalChildCount.set(newValue);
        }

        int getTotalChildCount() {
            return totalChildCount.get();
        }

        BooleanProperty getLoadingProperty() {
            return isLoading;
        }

        void setLoading(boolean value) {
            isLoading.set(value);
        }

        ObservableList<OmeroRawObject> getImageList() {
            return orphanedImageList;
        }

        @Override
        int getNChildren() {
            return currentChildCount.get();
        }

        @Override
        String getAPIURLString() {
            return "";
        }
    }

    static class Project extends OmeroRawObject {

        private final String url;
        private final String description;
        private final int childCount;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return childCount;
        }

        String getDescription() {
            return description;
        }


        public Project(String url, ProjectData projectData, long id, OmeroRawObjectType type, OmeroRawObject parent, omero.model.Experimenter user, ExperimenterGroup group) {
            this.url = url;
            this.description = projectData.getDescription();
            this.childCount = projectData.asProject().sizeOfDatasetLinks();
            super.data = projectData;
            super.setId(id);
            super.setName(projectData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(projectData.getGroupId(), group.getName().getValue()));
        }
    }

    static class Dataset extends OmeroRawObject {

        private final String url;
        private final String description;
        private final int childCount;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return childCount;
        }

        String getDescription() {
            return description;
        }


        public Dataset(String url, DatasetData datasetData, long id, OmeroRawObjectType type, OmeroRawObject parent, omero.model.Experimenter user, ExperimenterGroup group) {
            this.url = url;
            this.description = datasetData.getDescription();
            this.childCount = datasetData.asDataset().sizeOfImageLinks();
            super.data = datasetData;

            super.setId(id);
            super.setName(datasetData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(datasetData.getGroupId(), group.getName().getValue()));
        }
    }


    static class Screen extends OmeroRawObject {

        private final String url;
        private final String description;
        private final int childCount;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return childCount;
        }

        String getDescription() {
            return description;
        }


        public Screen(String url, ScreenData screenData, long id, OmeroRawObjectType type, OmeroRawObject parent, omero.model.Experimenter user, ExperimenterGroup group) {
            this.url = url;
            this.description = screenData.getDescription();
            this.childCount = screenData.asScreen().sizeOfPlateLinks();
            super.data = screenData;
            super.setId(id);
            super.setName(screenData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(screenData.getGroupId(), group.getName().getValue()));
        }
    }


    static class Plate extends OmeroRawObject {

        private final String url;
        private final String description;
        private final int plateAquisitionCount;
        private final int childCount;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return childCount;
        }

        String getDescription() {
            return description;
        }


        public Plate(String url, PlateData plateData, long id, OmeroRawObjectType type, OmeroRawObject parent, omero.model.Experimenter user, ExperimenterGroup group) {
            this.url = url;
            this.description = plateData.getDescription();
            this.plateAquisitionCount = plateData.asPlate().sizeOfPlateAcquisitions(); // TODO see that also
            this.childCount = plateData.asPlate().sizeOfWells(); // TODO seem to does not work
            super.data = plateData;
            super.setId(id);
            super.setName(plateData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(plateData.getGroupId(), group.getName().getValue()));
        }
    }


    // TODo see how to deal with that => not really understandable
    static class PlateAcquisition extends OmeroRawObject {

        private final String url;
        private final String description;
        private final int timePoint;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return 0;
        }

        String getDescription() {
            return description;
        }


        public PlateAcquisition(String url, PlateAcquisitionData plateAcquisitionData, long id, int timePoint, OmeroRawObjectType type, OmeroRawObject parent, omero.model.Experimenter user, ExperimenterGroup group) {
            this.url = url;
            this.description = plateAcquisitionData.getDescription();
            this.timePoint = timePoint;
            super.data = plateAcquisitionData;
            super.setId(id);
            super.setName(plateAcquisitionData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(plateAcquisitionData.getGroupId(), group.getName().getValue()));
        }
    }


    static class Well extends OmeroRawObject {

        private final String url;
        private final String description;
        private final int childCount;
        private final int timePoint;

        @Override
        String getAPIURLString() {
            return url;
        }

        @Override
        int getNChildren() {
            return childCount;
        }

        String getDescription() {
            return description;
        }

        int getTimePoint() {
            return timePoint;
        }


        public Well(String url, WellData wellData, long id, int timePoint, OmeroRawObjectType type, OmeroRawObject parent, omero.model.Experimenter user, ExperimenterGroup group) {
            this.url = url;
            this.description = "";
            this.childCount = wellData.asWell().sizeOfWellSamples();
            this.timePoint = timePoint;
            super.data = wellData;
            super.setId(id);
            super.setName("" + (char)(wellData.getRow() + 65) + (wellData.getColumn() < 9 ? ""+ 0 + (wellData.getColumn() + 1) : ""+(wellData.getColumn() + 1)));
            super.setType(type.toString());
            super.setParent(parent);

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(wellData.getGroupId(), group.getName().getValue()));
        }
    }

    static class Image extends OmeroRawObject {
        private final String url;
        private long acquisitionDate = -1;
        private final PixelInfo pixels;


        @Override
        String getAPIURLString() {
            return url;
        }

        long getAcquisitionDate() {
            return acquisitionDate;
        }

        int[] getImageDimensions() {
            return pixels.getImageDimensions();
        }

        PhysicalSize[] getPhysicalSizes() {
            return pixels.getPhysicalSizes();
        }

        String getPixelType() {
            return pixels.getPixelType();
        }


        public Image(String url, ImageData imageData, long id, OmeroRawObjectType type, OmeroRawObject parent, omero.model.Experimenter user, ExperimenterGroup group) {
            this.url = url;
            this.acquisitionDate = imageData.getAcquisitionDate()==null ? -1 : imageData.getAcquisitionDate().getTime();
            super.data = imageData;
            PixelsData pixData = imageData.getDefaultPixels();

            PixelInfo pixelInfo = new PixelInfo(pixData.getSizeX(), pixData.getSizeY(), pixData.getSizeC(), pixData.getSizeZ(), pixData.getSizeT(),
                    pixData.asPixels().getPhysicalSizeX()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeX().getUnit().toString(), pixData.asPixels().getPhysicalSizeX().getValue()),
                    pixData.asPixels().getPhysicalSizeY()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeY().getUnit().toString(), pixData.asPixels().getPhysicalSizeY().getValue()),
                    pixData.asPixels().getPhysicalSizeZ()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeZ().getUnit().toString(), pixData.asPixels().getPhysicalSizeZ().getValue()),
                    new ImageType(pixData.getPixelType()));

            this.pixels = pixelInfo;

            super.setId(id);
            super.setName(imageData.getName());
            super.setType(type.toString());
            super.setParent(parent);

            super.setOwner(new Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue()));

            super.setGroup(new Group(imageData.getGroupId(), group.getName().getValue()));
        }
    }


    static class Owner {
        private final long id;
        private String firstName = "";
        private String middleName = "";
        private String lastName = "";
        private String emailAddress = "";
        private String institution = "";
        private String username = "";

        // Singleton (with static factory)
        private static final Owner ALL_MEMBERS = new Owner(-1, "All members", "", "", "", "", "");

        public Owner(long id, String firstName, String middleName, String lastName, String emailAddress, String institution, String username) {
            this.id = Objects.requireNonNull(id);
            this.firstName = Objects.requireNonNull(firstName);
            this.middleName = Objects.requireNonNull(middleName);
            this.lastName = Objects.requireNonNull(lastName);

            this.emailAddress = emailAddress;
            this.institution = institution;
            this.username = username;
        }

        String getName() {
            // We never know if a deserialized Owner will have all the necessary information
            this.firstName = this.firstName == null ? "" : this.firstName;
            this.middleName = this.middleName == null ? "" : this.middleName;
            this.lastName = this.lastName == null ? "" : this.lastName;
            return this.firstName + " " + (this.middleName.isEmpty() ? "" : this.middleName + " ") + this.lastName;
        }

        long getId() {
            return id;
        }

        /**
         * Dummy {@code Owner} object (singleton instance) to represent all owners.
         * @return owner
         */
        static Owner getAllMembersOwner() {
            return ALL_MEMBERS;
        }

        @Override
        public String toString() {
            List<String> list = new ArrayList<>(Arrays.asList("Owner: " + getName(), emailAddress, institution, username));
            list.removeAll(Arrays.asList("", null));
            return String.join(", ", list);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (!(obj instanceof Owner))
                return false;
            return ((Owner)obj).id == this.id;
        }
    }

    static class Group implements Comparable {
        private final long id;
        private final String name;

        // Singleton (with static factory)
        private static final Group ALL_GROUPS = new Group(-1, "All groups");

        public Group(long id, String name) {
            this.id = id;
            this.name = name;
        }

        /**
         * Dummy {@code Group} object (singleton instance) to represent all groups.
         * @return group
         */
        public static Group getAllGroupsGroup() {
            return ALL_GROUPS;
        }

        public String getName() {
            return this.name;
        }

        long getId() {
            return id;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (!(obj instanceof Group))
                return false;
            return ((Group)obj).id == this.id;
        }

        @Override
        public int compareTo(Object o) {
            Group group = (Group)o;
            return this.getName().compareTo(group.getName());
        }
    }

    static class PixelInfo {
        private final int width;
        private final int height;
        private final int z;
        private final int c;
        private final int t;
        private final PhysicalSize physicalSizeX;
        private final PhysicalSize physicalSizeY;
        private final PhysicalSize physicalSizeZ;
        private final ImageType imageType;

        int[] getImageDimensions() {
            return new int[] {width, height, c, z, t};
        }

        PhysicalSize[] getPhysicalSizes() {
            return new PhysicalSize[] {physicalSizeX, physicalSizeY, physicalSizeZ};
        }

        String getPixelType() {
            return imageType.getValue();
        }

        public PixelInfo(int width, int height,int c, int z, int t, PhysicalSize pSizeX, PhysicalSize pSizeY, PhysicalSize pSizeZ, ImageType imageType){
            this.width = width;
            this.height = height;
            this.c = c;
            this.t = t;
            this.z = z;
            this.physicalSizeX = pSizeX;
            this.physicalSizeY = pSizeY;
            this.physicalSizeZ = pSizeZ;
            this.imageType = imageType;
        }
    }

    static class PhysicalSize {

        private final String symbol;
        private final double value;


        String getSymbol() {
            return symbol;
        }
        double getValue() {
            return value;
        }

        public PhysicalSize(String symbol, double value){
            this.symbol = symbol;
            this.value = value;
        }

    }

    static class ImageType {

        private final String value;

        String getValue() {
            return value;
        }

        public ImageType(String value){
            this.value = value;
        }

    }


    /**
     * Both in OmeroRawAnnotations and in OmeroRawObjects.
     */
    static class Permission {

        private final boolean canDelete;

        private final boolean canAnnotate;

        private final boolean canLink;

        private final boolean canEdit;

        // Only in OmeroRawObjects
        private final boolean isUserWrite;

        private final boolean isUserRead;

        private final boolean isWorldWrite;

        private final boolean isWorldRead;

        private final boolean isGroupWrite;

        private final boolean isGroupRead;

        private final boolean isGroupAnnotate;

        private String perm;

        public Permission(PermissionData permissions, OmeroRawClient client){
            this.isGroupAnnotate = permissions.isGroupAnnotate();
            this.isGroupRead = permissions.isGroupRead();
            this.isGroupWrite = permissions.isGroupWrite();
            this.isUserRead = permissions.isUserRead();
            this.isUserWrite = permissions.isUserWrite();
            this.isWorldRead = permissions.isWorldRead();
            this.isWorldWrite = permissions.isWorldWrite();

            this.canAnnotate = client.getLoggedInUser().canAnnotate();
            this.canDelete = client.getLoggedInUser().canDelete();
            this.canEdit = client.getLoggedInUser().canEdit();
            this.canLink = client.getLoggedInUser().canLink();
        }
    }


    static class Link {

        private int id;

        private final Owner owner;

        public Link(Owner owner){
            this.owner = owner;
        }

        Owner getOwner() {
            return owner;
        }
    }


    static class Experimenter {

        private final int id;
        private final String omeName;
        private final String firstName;
        private final String lastName;

        /**
         * Return the Id of this {@code Experimenter}.
         * @return id
         */
        int getId() {
            return id;
        }

        /**
         * Return the full name (first name + last name) of this {@code Experimenter}.
         * @return full name
         */
        String getFullName() {
            return firstName + " " + lastName;
        }

        public Experimenter(omero.model.Experimenter experimenter){
            this.id = experimenter.getId()==null ? -1 : (int)experimenter.getId().getValue();
            this.omeName = experimenter.getId()==null ? "" : experimenter.getOmeName().getValue();
            this.firstName = experimenter.getId()==null ? "" : experimenter.getFirstName().getValue();
            this.lastName = experimenter.getId()==null ? "" : experimenter.getLastName().getValue();

        }
    }
}
