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


/**
 * Class regrouping all OMERO objects used to create the browser.
 *
 * @author Melvin Gelbard
 * @author Rémy Dornier
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
        private String description;

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

        /**
         * Set the object description
         * @param description
         */
        void setDescription(String description){this.description = description;}

        /**
         * Returns the object description
         * @return description
         */
        String getDescription(){return this.description;}


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
        private final String url;
        protected Server(URI uri) {
            this.url = uri.toString();
            super.setId(-1);
            super.setType("Server");
            super.setOwner(null);
            super.setName("");
            super.setGroup(null);
            super.setParent(null);
            super.setDescription("");
        }
        protected String getUrl(){return this.url;}
    }


    protected static class OrphanedFolder extends OmeroRawObject {
        private List<OmeroRawObject> orphanedImageList = new ArrayList<>();

        protected OrphanedFolder(OmeroRawObject parent,
                                 ExperimenterWrapper user, GroupWrapper group) {
            super.setWrapper(null);
            super.setDescription("This is a virtual container with orphaned images. These images are not linked anywhere.");
            super.setId(-1);
            super.setName("Orphaned Images");
            super.setType(OmeroRawObjectType.ORPHANED_FOLDER.toString());
            super.setParent(parent);
            super.setOwner(user == null ? Owner.ALL_MEMBERS : new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
        }

        List<OmeroRawObject> getImageList() {
            return this.orphanedImageList;
        }

        void addOrphanedImages(List<OmeroRawObject> images){
            this.orphanedImageList.addAll(images);
        }

        @Override
        int getNChildren() {
            return orphanedImageList.size();
        }
    }

    protected static class Project extends OmeroRawObject {
        private final int childCount;

        @Override
        protected int getNChildren() {
            return childCount;
        }


        protected Project(ProjectWrapper projectWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent,
                       ExperimenterWrapper user, GroupWrapper group) {
            this.childCount = projectWrapper.asDataObject().asProject().sizeOfDatasetLinks();

            super.setWrapper(projectWrapper);
            super.setDescription(projectWrapper.getDescription());
            super.setId(id);
            super.setName(projectWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
        }
    }

    protected static class Dataset extends OmeroRawObject {
        private final int childCount;

        @Override
        int getNChildren() {
            return childCount;
        }


        protected Dataset( DatasetWrapper datasetWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent,
                       ExperimenterWrapper user, GroupWrapper group) {
            this.childCount = datasetWrapper.asDataObject().asDataset().sizeOfImageLinks();

            super.setWrapper(datasetWrapper);
            super.setDescription(datasetWrapper.getDescription());
            super.setId(id);
            super.setName(datasetWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
        }
    }


    protected static class Screen extends OmeroRawObject {
        private final int childCount;

        @Override
        int getNChildren() {
            return childCount;
        }


        protected Screen(ScreenWrapper screenWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent, ExperimenterWrapper user, GroupWrapper group) {
            this.childCount = screenWrapper.asDataObject().asScreen().sizeOfPlateLinks();

            super.setWrapper(screenWrapper);
            super.setDescription(screenWrapper.getDescription());
            super.setId(id);
            super.setName(screenWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
        }
    }


    protected static class Plate extends OmeroRawObject {
        private final int plateAquisitionCount;
        private final int childCount;


        @Override
        int getNChildren() {
            return childCount;
        }


        protected Plate(PlateWrapper plateWrapper, long id, OmeroRawObjectType type, OmeroRawObject parent, ExperimenterWrapper user, GroupWrapper group) {
            this.plateAquisitionCount = plateWrapper.asDataObject().asPlate().sizeOfPlateAcquisitions();
            this.childCount = plateWrapper.asDataObject().asPlate().sizeOfWells();

            super.setWrapper(plateWrapper);
            super.setDescription(plateWrapper.getDescription());
            super.setId(id);
            super.setName(plateWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
        }
    }


    // TODo see how to deal with that => not really understandable
    protected static class PlateAcquisition extends OmeroRawObject {
        private final int timePoint;

        @Override
        int getNChildren() {
            return 0;
        }


        protected PlateAcquisition(PlateAcquisitionWrapper plateAcquisitionWrapper, long id, int timePoint,
                                OmeroRawObjectType type, OmeroRawObject parent, ExperimenterWrapper user, GroupWrapper group) {
            this.timePoint = timePoint;

            super.setWrapper(plateAcquisitionWrapper);
            super.setDescription(plateAcquisitionWrapper.getDescription());
            super.setId(id);
            super.setName(plateAcquisitionWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
        }
    }


    protected static class Well extends OmeroRawObject {
        private final int childCount;
        private final int timePoint;


        @Override
        int getNChildren() {
            return childCount;
        }
        int getTimePoint() {
            return timePoint;
        }


        protected Well(WellWrapper wellWrapper, long id, int timePoint, OmeroRawObjectType type, OmeroRawObject parent,
                    ExperimenterWrapper user, GroupWrapper group) {
            this.childCount = wellWrapper.asDataObject().asWell().sizeOfWellSamples();
            this.timePoint = timePoint;

            super.setWrapper(wellWrapper);
            super.setDescription(wellWrapper.getDescription());
            super.setId(id);
            super.setName("" + (char)(wellWrapper.getRow() + 65) + (wellWrapper.getColumn() < 9 ? "0"+ (wellWrapper.getColumn() + 1) : String.valueOf(wellWrapper.getColumn() + 1)));
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
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

            PixelsData pixData = imageWrapper.getPixels().asDataObject();
            this.pixels = new PixelInfo(pixData.getSizeX(), pixData.getSizeY(), pixData.getSizeC(), pixData.getSizeZ(), pixData.getSizeT(),
                    pixData.asPixels().getPhysicalSizeX()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeX().getUnit().toString(), pixData.asPixels().getPhysicalSizeX().getValue()),
                    pixData.asPixels().getPhysicalSizeY()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeY().getUnit().toString(), pixData.asPixels().getPhysicalSizeY().getValue()),
                    pixData.asPixels().getPhysicalSizeZ()==null ? new PhysicalSize("", -1) : new PhysicalSize(pixData.asPixels().getPhysicalSizeZ().getUnit().toString(), pixData.asPixels().getPhysicalSizeZ().getValue()),
                    new ImageType(pixData.getPixelType()));

            super.setWrapper(imageWrapper);
            super.setDescription(imageWrapper.getDescription());
            super.setId(id);
            super.setName(imageWrapper.getName());
            super.setType(type.toString());
            super.setParent(parent);
            super.setOwner(new Owner(user));
            super.setGroup(new Group(group, group.getId(), group.getName()));
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

            // TODO remove the try/catch when the omero.ij plugin integrate the version 5.9.0 of java gateway
            // this.middleName = experimenterWrapper.getMiddleName() == null ? "" : experimenterWrapper.getMiddleName();
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
        private final GroupWrapper wrapper;

        // Singleton (with static factory)
        private static final Group ALL_GROUPS = new Group(null, -1, "All groups");

        protected Group(GroupWrapper groupWrapper, long id, String name) {
            this.id = id;
            this.name = name;
            this.wrapper = groupWrapper;
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

        protected GroupWrapper getWrapper(){
            return this.wrapper;
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
}
