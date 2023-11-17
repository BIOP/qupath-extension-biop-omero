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

import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.meta.GroupWrapper;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.GenericRepositoryObjectWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.PlateAcquisitionWrapper;
import fr.igred.omero.repository.PlateWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import fr.igred.omero.repository.ScreenWrapper;
import fr.igred.omero.repository.WellWrapper;
import omero.gateway.model.PixelsData;
import omero.gateway.model.DataObject;
import omero.gateway.model.PermissionData;

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

    public enum OmeroRawObjectType {
        SERVER("Server"),
        PROJECT("Project"),
        DATASET("Dataset"),
        IMAGE("Image"),
        PLATE("Plate"),
        WELL("Well"),
        SCREEN("Screen"),
        PLATE_ACQUISITION("Plate acquisition"),
        ORPHANED_FOLDER("Orphaned Folder"),

        // Default if unknown
        UNKNOWN("Unknown");

        private final String displayedName;

        OmeroRawObjectType(String displayedName) {
            this.displayedName = displayedName;
        }

        static OmeroRawObjectType fromString(String text) {
            for (var type : OmeroRawObjectType.values()) {
                if (type.displayedName.equalsIgnoreCase(text))
                    return type;
            }
            return UNKNOWN;
        }

        @Override
        public String toString() {
            return displayedName;
        }
    }

    protected static abstract class OmeroRawObject {
        private long id = -1;
        protected String name;
        protected String type;
        private Owner owner;
        private Group group;
        private OmeroRawObject parent;

        private GenericRepositoryObjectWrapper<? extends DataObject> wrapper;

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
        GenericRepositoryObjectWrapper<? extends DataObject> getWrapper() {
            return wrapper;
        }

        /**
         * Set the data of this object
         * @param obj
         */
        void setWrapper(GenericRepositoryObjectWrapper<? extends DataObject> obj) {
            this.wrapper = obj;
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

    protected static class Server extends OmeroRawObject {
        private String url;
        protected Server(URI uri) {
            super.id = -1;
            super.type = "Server";
            super.owner = null;
            super.name = "";
            super.group = null;
            super.parent = null;
            this.url = uri.toString();
        }
        protected String getUrl(){return this.url;}
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
    protected static class OrphanedFolder extends OmeroRawObject {

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

        protected OrphanedFolder(ObservableList<OmeroRawObject> orphanedImageList) {
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
    }

    protected static class Project extends OmeroRawObject {
        private final String description;
        private final int childCount;

        @Override
        protected int getNChildren() {
            return childCount;
        }
        protected String getDescription() {
            return description;
        }


        protected Project(ProjectWrapper projectWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent,
                       ExperimenterWrapper user, GroupWrapper group) {
            this.description = projectWrapper.getDescription();
            this.childCount = projectWrapper.asDataObject().asProject().sizeOfDatasetLinks();
            super.wrapper = projectWrapper;
            super.setId(id);
            super.setName(projectWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group.getId(), group.getName()));
        }
    }

    protected static class Dataset extends OmeroRawObject {
        private final String description;
        private final int childCount;

        @Override
        int getNChildren() {
            return childCount;
        }
        String getDescription() {
            return description;
        }


        protected Dataset( DatasetWrapper datasetWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent,
                       ExperimenterWrapper user, GroupWrapper group) {
            this.description = datasetWrapper.getDescription();
            this.childCount = datasetWrapper.asDataObject().asDataset().sizeOfImageLinks();
            super.wrapper = datasetWrapper;

            super.setId(id);
            super.setName(datasetWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group.getId(), group.getName()));
        }
    }


    protected static class Screen extends OmeroRawObject {
        private final String description;
        private final int childCount;

        @Override
        int getNChildren() {
            return childCount;
        }
        String getDescription() {
            return description;
        }


        protected Screen(ScreenWrapper screenWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent, ExperimenterWrapper user, GroupWrapper group) {
            this.description = screenWrapper.getDescription();
            this.childCount = screenWrapper.asDataObject().asScreen().sizeOfPlateLinks();
            super.wrapper = screenWrapper;
            super.setId(id);
            super.setName(screenWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group.getId(), group.getName()));
        }
    }


    protected static class Plate extends OmeroRawObject {
        private final String description;
        private final int plateAquisitionCount;
        private final int childCount;


        @Override
        int getNChildren() {
            return childCount;
        }
        String getDescription() {
            return description;
        }


        protected Plate(PlateWrapper plateWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent, ExperimenterWrapper user, GroupWrapper group) {
            this.description = plateWrapper.getDescription();
            this.plateAquisitionCount = plateWrapper.asDataObject().asPlate().sizeOfPlateAcquisitions();
            this.childCount = plateWrapper.asDataObject().asPlate().sizeOfWells();
            super.wrapper = plateWrapper;
            super.setId(id);
            super.setName(plateWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group.getId(), group.getName()));
        }
    }


    // TODo see how to deal with that => not really understandable
    protected static class PlateAcquisition extends OmeroRawObject {
        private final String description;
        private final int timePoint;

        @Override
        int getNChildren() {
            return 0;
        }
        String getDescription() {
            return description;
        }


        protected PlateAcquisition(PlateAcquisitionWrapper plateAcquisitionWrapper, long id, int timePoint,
                                OmeroRawObjectType type, OmeroRawObject parent, ExperimenterWrapper user, GroupWrapper group) {
            this.description = plateAcquisitionWrapper.getDescription();
            this.timePoint = timePoint;
            super.wrapper = plateAcquisitionWrapper;
            super.setId(id);
            super.setName(plateAcquisitionWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group.getId(), group.getName()));
        }
    }


    protected static class Well extends OmeroRawObject {
        private final String description;
        private final int childCount;
        private final int timePoint;


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


        protected Well(WellWrapper wellWrapper, long id, int timePoint, OmeroRawObjectType type, OmeroRawObject parent,
                    ExperimenterWrapper user, GroupWrapper group) {
            this.description = "";
            this.childCount = wellWrapper.asDataObject().asWell().sizeOfWellSamples();
            this.timePoint = timePoint;
            super.wrapper = wellWrapper;
            super.setId(id);
            super.setName("" + (char)(wellWrapper.getRow() + 65) + (wellWrapper.getColumn() < 9 ? "0"+ (wellWrapper.getColumn() + 1) : String.valueOf(wellWrapper.getColumn() + 1)));
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group.getId(), group.getName()));
        }
    }

    protected static class Image extends OmeroRawObject {
        private long acquisitionDate = -1;
        private final PixelInfo pixels;

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

        protected Image(ImageWrapper imageWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent,
                     ExperimenterWrapper user, GroupWrapper group) {
            this.acquisitionDate = imageWrapper.getAcquisitionDate()==null ? -1 : imageWrapper.getAcquisitionDate().getTime();
            super.wrapper = imageWrapper;
            PixelsData pixData = imageWrapper.getPixels().asDataObject();

            PixelInfo pixelInfo = new PixelInfo(pixData.getSizeX(), pixData.getSizeY(), pixData.getSizeC(), pixData.getSizeZ(), pixData.getSizeT(),
                    pixData.asPixels().getPhysicalSizeX()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeX().getUnit().toString(), pixData.asPixels().getPhysicalSizeX().getValue()),
                    pixData.asPixels().getPhysicalSizeY()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeY().getUnit().toString(), pixData.asPixels().getPhysicalSizeY().getValue()),
                    pixData.asPixels().getPhysicalSizeZ()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeZ().getUnit().toString(), pixData.asPixels().getPhysicalSizeZ().getValue()),
                    new ImageType(pixData.getPixelType()));

            this.pixels = pixelInfo;

            super.setId(id);
            super.setName(imageWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group.getId(), group.getName()));
        }
    }


    protected static class Owner {
        private final long id;
        private String firstName = "";
        private String middleName = "";
        private String lastName = "";
        private String emailAddress = "";
        private String institution = "";
        private String username = "";
        private ExperimenterWrapper wrapper;

        // Singleton (with static factory)
        private static final Owner ALL_MEMBERS = new Owner(null,-1, "All members", "", "", "", "", "");

        protected Owner(ExperimenterWrapper experimenterWrapper) {
            this.id = experimenterWrapper.getId();
            this.firstName = experimenterWrapper.getFirstName()==null ? "" : experimenterWrapper.getFirstName();
            //TODO see when this issue is solved https://github.com/ome/omero-gateway-java/issues/83
            String middleName = "";
            try{
                middleName = experimenterWrapper.getMiddleName();
            }catch (Exception e){

            }
            this.middleName = middleName;
            this.lastName = experimenterWrapper.getLastName()==null ? "" : experimenterWrapper.getLastName();

            this.emailAddress = experimenterWrapper.getEmail()==null ? "" : experimenterWrapper.getEmail();;
            this.institution = experimenterWrapper.getInstitution()==null ? "" : experimenterWrapper.getInstitution();;
            this.username = experimenterWrapper.getUserName()==null ? "" : experimenterWrapper.getUserName();;
            this.wrapper = experimenterWrapper;
        }

        private Owner(ExperimenterWrapper experimenterWrapper, long id, String firstName, String middleName, String lastName, String emailAddress, String institution, String username){
            this.id = id;
            this.firstName = firstName;
            this.middleName = middleName;
            this.lastName = lastName;

            this.emailAddress = emailAddress;
            this.institution = institution;
            this.username = username;
            this.wrapper = experimenterWrapper;
        }

        String getName() {
            return this.firstName + " " + (this.middleName.isEmpty() ? "" : this.middleName + " ") + this.lastName;
        }

        long getId() {
            return id;
        }

        ExperimenterWrapper getWrapper(){return wrapper;}

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

    protected static class Group implements Comparable {
        private final long id;
        private final String name;

        // Singleton (with static factory)
        private static final Group ALL_GROUPS = new Group(-1, "All groups");

        protected Group(long id, String name) {
            this.id = id;
            this.name = name;
        }

        /**
         * Dummy {@code Group} object (singleton instance) to represent all groups.
         * @return group
         */
        protected static Group getAllGroupsGroup() {
            return ALL_GROUPS;
        }

        protected String getName() {
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

    protected static class PixelInfo {
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

        protected PixelInfo(int width, int height,int c, int z, int t, PhysicalSize pSizeX, PhysicalSize pSizeY, PhysicalSize pSizeZ, ImageType imageType){
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

    protected static class PhysicalSize {
        private final String symbol;
        private final double value;

        String getSymbol() {
            return symbol;
        }
        double getValue() {
            return value;
        }

        protected PhysicalSize(String symbol, double value){
            this.symbol = symbol;
            this.value = value;
        }

    }

    protected static class ImageType {
        private final String value;

        String getValue() {
            return value;
        }

        protected ImageType(String value){
            this.value = value;
        }
    }


    /**
     * Both in OmeroRawAnnotations and in OmeroRawObjects.
     */
    protected static class Permission {
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

        protected Permission(PermissionData permissions, OmeroRawClient client){
            this.isGroupAnnotate = permissions.isGroupAnnotate();
            this.isGroupRead = permissions.isGroupRead();
            this.isGroupWrite = permissions.isGroupWrite();
            this.isUserRead = permissions.isUserRead();
            this.isUserWrite = permissions.isUserWrite();
            this.isWorldRead = permissions.isWorldRead();
            this.isWorldWrite = permissions.isWorldWrite();

            ExperimenterWrapper loggedInUser = client.getLoggedInUser();
            this.canAnnotate = loggedInUser.canAnnotate();
            this.canDelete = loggedInUser.canDelete();
            this.canEdit = loggedInUser.canEdit();
            this.canLink = loggedInUser.canLink();
        }
    }


    protected static class Link {
        private int id;
        private final Owner owner;

        protected Link(Owner owner){
            this.owner = owner;
        }

        Owner getOwner() {
            return owner;
        }
    }


    protected static class Experimenter {
        private final long id;
        private final String omeName;
        private final String firstName;
        private final String lastName;

        /**
         * Return the Id of this {@code Experimenter}.
         * @return id
         */
        long getId() {
            return id;
        }

        /**
         * Return the full name (first name + last name) of this {@code Experimenter}.
         * @return full name
         */
        String getFullName() {
            return firstName + " " + lastName;
        }

        protected Experimenter(ExperimenterWrapper experimenter){
            this.id = experimenter.getId();
            this.omeName = experimenter.getUserName()==null ? "" : experimenter.getUserName();
            this.firstName = experimenter.getFirstName()==null ? "" : experimenter.getFirstName();
            this.lastName = experimenter.getFirstName()==null ? "" : experimenter.getLastName();
        }
    }
}
