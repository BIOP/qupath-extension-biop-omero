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

import fr.igred.omero.exception.AccessException;
import fr.igred.omero.meta.GroupWrapper;
import fr.igred.omero.meta.PlaneInfoWrapper;
import fr.igred.omero.repository.ChannelWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.PixelsWrapper;
import jdk.jshell.execution.Util;
import loci.common.DataTools;
import loci.formats.FormatException;
import omero.ServerError;
import omero.api.RawPixelsStorePrx;
import omero.api.ResolutionDescription;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.MetadataFacility;

import omero.gateway.model.ImageData;
import omero.gateway.model.PixelsData;

import omero.model.ChannelBinding;
import omero.model.Length;
import omero.model.RenderingDef;
import omero.model.Time;
import omero.model.enums.UnitsLength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClients;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawScripting;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.utils.Utils;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.fx.dialogs.Dialogs;

import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectReader;
import qupath.lib.regions.RegionRequest;

import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferDouble;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.net.URI;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ImageServer that reads pixels using the OMERO-JAVA gateway
 * <p>
 * Note that this ImageServer provides access to the raw data (not JPEG rendered).
 * Fluorescence and bright-field images from OMERO can be open with it.
 * 
 * @author Olivier Burri and Rémy Dornier
 *
 */
public class OmeroRawImageServer extends AbstractTileableImageServer implements PathObjectReader {
	private static final Logger logger = LoggerFactory.getLogger(OmeroRawImageServer.class);
	private static final int MIN_TILE_SIZE = 512;
	private static final int MAX_TILE_SIZE = 2048;

	private final URI uri;
	private final String[] args;
	private final String host;
	private final String scheme;
	private final int port;

	private ImageServerMetadata originalMetadata;

	/**
	 * Image OMERO ID
	 */
	private Long imageID;

	/**
	 * Client used to open this image.
	 */
	private OmeroRawClient client; //not final anymore because we need to update the client for the image when we use sudo connection

	/**
	 * Image Omero Wrapper
	 */
	private ImageWrapper imageWrapper;


	/**
	 * Pool of readers for use with this server.
	 */
	private ReaderPool readerPool;

	/**
	 * ColorModel to use with all BufferedImage requests.
	 */
	private ColorModel colorModel;
	private boolean isRGB;


	/**
	 * Instantiate an OMERO server.
	 *
	 * Note that there are five URI options currently supported:
	 * <ul>
	 * 	<li> Copy and paste from web viewer ("{@code /host/webclient/img_detail/id/}")</li>
	 *  <li> Copy and paste from the 'Link' button ("{@code /host/webclient/?show=id}")</li>
	 *  <li> Copy and paste from the old viewer ("{@code /host/webgateway/img_detail/id}")</li>
	 *  <li> Copy and paste from the new viewer ("{@code /host/iviewer/?images=id}")</li>
	 *  <li> Id provided as only fragment after host</li>
	 * </ul>
	 * The fifth option could be removed.
	 *
	 * @param uri
	 * @param client
	 * @param args
	 * @throws IOException
	 */
	OmeroRawImageServer(URI uri, OmeroRawClient client, String...args)
			throws IOException, ServerError, DSOutOfServiceException, ExecutionException, DSAccessException {
		super();
		this.uri = uri;
		this.scheme = uri.getScheme();
		this.host = uri.getHost();
		this.port = uri.getPort();
		this.client = client;
		buildMetadata();
		// Args are stored in the JSON - passwords and usernames must not be included!
		// Do an extra check to ensure someone hasn't accidentally passed one
		var invalid = Arrays.asList("--password", "-p", "-u", "--username", "-password");
		for (String s : args) {
			String arg = s.toLowerCase().strip();
			if (invalid.contains(arg)) {
				throw new IllegalArgumentException("Cannot build server with arg " + arg);
			}
		}
		this.args = args;
		
		// Add URI to the client's list of URIs
		client.addURI(uri);
	}

	/**
	 * read image metadata and build a QuPath ImageServerMetadata object.
	 *
	 * @throws ServerError
	 * @throws DSOutOfServiceException
	 * @throws ExecutionException
	 * @throws DSAccessException
	 */
	protected void buildMetadata()
			throws ServerError, DSOutOfServiceException, ExecutionException, DSAccessException {

		long startTime = System.currentTimeMillis();

		// Create variables for metadata
		int width = 0, height = 0, nChannels = 1, nZSlices = 1, nTimepoints = 1, tileWidth = 0, tileHeight = 0;
		double pixelWidth = Double.NaN, pixelHeight = Double.NaN, zSpacing = Double.NaN, magnification = Double.NaN;
		TimeUnit timeUnit = null;

		String uriQuery = uri.getQuery();
		if (uriQuery != null && !uriQuery.isEmpty() && uriQuery.startsWith("show=image-")) {
			Pattern pattern = Pattern.compile("show=image-(\\d+)");
			Matcher matcher = pattern.matcher(uriQuery);
			if (matcher.find())
				this.imageID = Long.valueOf(matcher.group(1));
		}
		if (this.imageID == null)
			this.imageID = Long.valueOf(uri.getFragment());


		// Create a reader & extract the metadata
		readerPool = new ReaderPool(this.client, this.imageID);
		LocalReaderWrapper readerWrapper = readerPool.getMainReader();
		PixelsWrapper meta = readerWrapper.getPixelsWrapper();
		this.client = readerPool.getClient();
		this.imageWrapper = this.client.getSimpleClient().getImage(imageID);

		// There is just one series per image ID
		synchronized (readerWrapper) {
			RawPixelsStorePrx reader = readerWrapper.getReader();
			String name = meta.asDataObject().getImage().getName();

			long sizeX = meta.getSizeX();
			long sizeY = meta.getSizeY();

			int nResolutions = readerWrapper.nLevels;
			for (int r = 1; r < nResolutions; r++) {
				int sizeXR = readerWrapper.imageSizeX[r];
				int sizeYR = readerWrapper.imageSizeY[r];
				if (sizeXR <= 0 || sizeYR <= 0 || sizeXR > sizeX || sizeYR > sizeY)
					throw new IllegalArgumentException("Resolution " + r + " size " + sizeXR + " x " + sizeYR + " invalid!");
			}

			// Try getting the magnification
			try {
				MetadataFacility metaFacility = this.client.getSimpleClient().getMetadata();
				double magnificationObject = metaFacility.getImageAcquisitionData(client.getSimpleClient().getCtx(), imageID).getObjective().getNominalMagnification();

				if (magnificationObject < 0) {
					logger.warn("Nominal objective magnification missing for image {}", imageID);
				} else
					magnification = magnificationObject;

			} catch (Exception e) {
				logger.debug("Unable to parse magnification: {}", e.getLocalizedMessage());
			}

			// Get the dimensions for the requested series
			// The first resolution is the highest, i.e. the largest image
			width = meta.getSizeX();
			height = meta.getSizeY();

			int[] tileSize = reader.getTileSize();

			tileWidth = tileSize[0];
			tileHeight = tileSize[1];
			nChannels = meta.getSizeC();

			// Make sure tile sizes are within range
			if (tileWidth <= 0)
				tileWidth = 256;
			if (tileHeight <= 0)
				tileHeight = 256;
			if (tileWidth > width)
				tileWidth = width;
			if (tileHeight > height)
				tileHeight = height;

			// Prepared to set channel colors
			List<ImageChannel> channels = new ArrayList<>();

			nZSlices = meta.getSizeZ();

			nTimepoints = meta.getSizeT();

			PixelType pixelType;
			switch (meta.getPixelType()) {
				case "float":
					pixelType = PixelType.FLOAT32;
					break;
				case "uint16":
					pixelType = PixelType.UINT16;
					break;
				case "uint8":
					pixelType = PixelType.UINT8;
					break;
				case "uint32":
					logger.warn("Pixel type is UINT32! This is not currently supported by QuPath.");
					pixelType = PixelType.UINT32;
					break;
				case "int16":
					pixelType = PixelType.INT16;
					break;
				case "double":
					pixelType = PixelType.FLOAT64;
					break;
				default:
					throw new IllegalArgumentException("Unsupported pixel type " + meta.getPixelType());
			}

			// Determine min/max values if we can
			int bpp = pixelType.getBitsPerPixel();

			Number minValue = null;
			Number maxValue = null;
			if (pixelType.isSignedInteger()) {
				minValue = -(int) Math.pow(2, bpp - 1);
				maxValue = (int) (Math.pow(2, bpp - 1) - 1);
			} else if (pixelType.isUnsignedInteger()) {
				maxValue = (int) (Math.pow(2, bpp) - 1);
			}

			// Try to read the default display colors for each channel from the file
			List<ChannelWrapper> channelMetadata = imageWrapper.getChannels(client.getSimpleClient());
			RenderingDef renderingSettings = client.getSimpleClient().getGateway().getRenderingSettingsService(client.getSimpleClient().getCtx()).getRenderingSettings(reader.getPixelsId());
			short nNullChannelName = 0;
			List<String> channelsNames = new ArrayList<>();
			for (int c = 0; c < nChannels; c++) {
				ome.xml.model.primitives.Color color = null;
				String channelName = null;
				Integer channelColor = null;

				try {
					channelName = channelMetadata.get(c).getName();
					ChannelBinding binding = renderingSettings.getChannelBinding(c);

					if (binding != null) {
						channelColor = ColorTools.packARGB(binding.getAlpha().getValue(), binding.getRed().getValue(), binding.getGreen().getValue(), binding.getBlue().getValue());
					}
				} catch (Exception e) {
					logger.warn("Unable to parse color", e);
				}

				if (channelColor == null) {
					// Select next available default color, or white (for grayscale) if only one channel
					if (nChannels == 1)
						channelColor = ColorTools.packRGB(255, 255, 255);
					else
						channelColor = ImageChannel.getDefaultChannelColor(c);
				}

				if (channelName == null || channelName.isBlank() || channelName.isEmpty()) {
					channelName = "Channel " + (c + 1);
					nNullChannelName++;
				}
				channels.add(ImageChannel.getInstance(channelName, channelColor));
				channelsNames.add(channelName);
			}

			// Update RGB status if needed - sometimes we might really have an RGB image, but the Bio-Formats flag doesn't show this -
			// and we want to take advantage of the optimizations where we can

			/*if (nChannels == 3 &&
					pixelType == PixelType.UINT8 &&
					channels.equals(ImageChannel.getDefaultRGBChannels())) {
				isRGB = true;
				colorModel = ColorModel.getRGBdefault();
			} else {
				colorModel = ColorModelFactory.createColorModel(pixelType, channels);
			}*/

			String imageFormat = imageWrapper.getFormat();

			Set<String> uniqueNames = new HashSet<>(channelsNames);

			if (nChannels == 3 && pixelType == PixelType.UINT8 &&
					(nNullChannelName == 3 || channels.equals(ImageChannel.getDefaultRGBChannels()) || (imageFormat.equals("CellSens") && uniqueNames.size() == 1))) {
				isRGB = true;
			}
			colorModel = ColorModelFactory.createColorModel(pixelType, channels);

			// Try parsing pixel sizes in micrometers
			double[] timepoints;
			try {
				Length xSize = meta.asDataObject().getPixelSizeX(UnitsLength.MICROMETER);
				Length ySize = meta.asDataObject().getPixelSizeY(UnitsLength.MICROMETER);
				if (xSize != null && ySize != null) {
					pixelWidth = xSize.getValue();
					pixelHeight = ySize.getValue();
				} else {
					pixelWidth = Double.NaN;
					pixelHeight = Double.NaN;
				}
				// If we have multiple z-slices, parse the spacing
				if (nZSlices > 1) {
					Length zSize = meta.asDataObject().getPixelSizeZ(UnitsLength.MICROMETER);
					if (zSize != null)
						zSpacing = zSize.getValue();
					else
						zSpacing = Double.NaN;
				}

                if (nTimepoints > 1) {
                    int lastTimepoint = -1;
                    int count = 0;
                    timepoints = new double[nTimepoints];
					meta.loadPlanesInfo(client.getSimpleClient());
					List<PlaneInfoWrapper> planesInfo = meta.getPlanesInfo();
                    logger.debug("Number of Timepoints: " + nTimepoints);
                    for (int plane = 0; plane < nTimepoints; plane++) {
						PlaneInfoWrapper currentPlane = planesInfo.get(plane);
                        int timePoint = planesInfo.get(plane).getTheT();
                        logger.debug("Checking " + timePoint);
                        if (timePoint != lastTimepoint) {
                            timepoints[count] = convertTimeToSecond(currentPlane.getDeltaT());
                            logger.debug(String.format("Timepoint %d: %.3f seconds", count, timepoints[count]));
                            lastTimepoint = timePoint;
                            count++;
                        }
                    }
                    timeUnit = TimeUnit.SECONDS;
                } else {
                    timepoints = new double[0];
                }
			} catch (Exception e) {
				logger.error("Error parsing metadata", e);
				pixelWidth = Double.NaN;
				pixelHeight = Double.NaN;
				zSpacing = Double.NaN;
				timepoints = null;
			}

			// Loop through the series & determine downsamples
			var resolutionBuilder = new ImageServerMetadata.ImageResolutionLevel.Builder(width, height)
					.addFullResolutionLevel();

			// I have seen czi files where the resolutions are not read correctly & this results in an IndexOutOfBoundsException
			for (int i = 1; i < nResolutions; i++) {
				try {
					int w = readerWrapper.imageSizeX[i];
					int h = readerWrapper.imageSizeY[i];

					if (w <= 0 || h <= 0) {
						logger.warn("Invalid resolution size {} x {}! Will skip this level, but something seems wrong...", w, h);
						continue;
					}
					// In some VSI images, the calculated downsamples for width & height can be wildly discordant,
					// and we are better off using defaults
					// Fix vsi issue see https://forum.image.sc/t/qupath-omero-weird-pyramid-levels/65484
					if (imageFormat.equals("CellSens")) {
						double downsampleX = (double)width / w;
						double downsampleY = (double)height / h;
						double downsample = Math.pow(2, i);

						if (!GeneralTools.almostTheSame(downsampleX, downsampleY, 0.01)) {
							logger.warn("Non-matching downsamples calculated for level {} ({} and {}); will use {} instead", i, downsampleX, downsampleY, downsample);
							resolutionBuilder.addLevel(downsample, w, h);
							continue;
						}
					}

					resolutionBuilder.addLevel(w, h);
				} catch (Exception e) {
					logger.warn("Error attempting to extract resolution " + i + " for " + name, e);
					break;
				}
			}

			// Set metadata
			String path = createID();
			ImageServerMetadata.Builder builder = new ImageServerMetadata.Builder(
					getClass(), path, width, height).
					name(name).
					channels(channels).
					sizeZ(nZSlices).
					sizeT(nTimepoints).
					levels(resolutionBuilder.build()).
					pixelType(pixelType).
					rgb(isRGB);

			if (Double.isFinite(magnification))
				builder = builder.magnification(magnification);

			if (timeUnit != null)
				builder = builder.timepoints(timeUnit, timepoints);

			if (Double.isFinite(pixelWidth + pixelHeight))
				builder = builder.pixelSizeMicrons(pixelWidth, pixelHeight);

			if (Double.isFinite(zSpacing))
				builder = builder.zSpacingMicrons(zSpacing);

			// Check the tile size if it is reasonable
			if (tileWidth >= MIN_TILE_SIZE && tileWidth <= MAX_TILE_SIZE && tileHeight >= MIN_TILE_SIZE && tileHeight <= MAX_TILE_SIZE)
				builder.preferredTileSize(tileWidth, tileHeight);
			originalMetadata = builder.build();
		}

		long endTime = System.currentTimeMillis();
		logger.debug(String.format("Initialization time: %d ms", endTime - startTime));
	}

	private double convertTimeToSecond(Time time){
		int unit = time.getUnit().value();
		double timeValue = time.getValue();

		if(unit < 7) {
			return timeValue * 1000 * Math.pow(10, 3 * (7 - unit));
		}else if (unit < 14){
			return timeValue * Math.pow(10, 10 - unit);
		}else if (unit < 21){
			return timeValue * 0.001 * Math.pow(10, 3 * (13 - unit));
		}else if (unit == 21) {
			return 60 * timeValue;
		}else if (unit == 22){
			return 3600* timeValue;
		}else if (unit == 23){
			return 86400 * timeValue;
		} else return timeValue;
	}

	@Override
	protected String createID() {
		return getClass().getName() + ": " + uri.toString();
	}

	@Override
	public Collection<URI> getURIs() {
		return Collections.singletonList(uri);
	}


	@Override
	public String getServerType() {
		return "OMERO raw server";
	}

	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}

	@Override
	protected BufferedImage readTile(TileRequest request) throws IOException {
		try {
			return readerPool.openImage(request, nChannels(),colorModel);
		} catch (InterruptedException e) {
			throw new IOException(e);
		}
	}
	
	
	@Override
	protected ServerBuilder<BufferedImage> createServerBuilder() {
		return ImageServerBuilder.DefaultImageServerBuilder.createInstance(
				OmeroRawImageServerBuilder.class,
				getMetadata(),
				uri,
				args);
	}

	/**
	 * Overridden method to generate a thumbnail even if it cannot be read from OMERO
	 * Code adapted from {@link qupath.lib.images.servers.AbstractImageServer}
	 *
	 * @param z
	 * @param t
	 * @return
	 * @throws IOException
	 */
	@Override
	public BufferedImage getDefaultThumbnail(int z, int t) throws IOException {
		int ind = nResolutions() - 1;
		double targetDownsample = Math.sqrt(getWidth() / 1024.0 * getHeight() / 1024.0);
		double[] downsamples = getPreferredDownsamples();
		while (ind > 0 && downsamples[ind-1] >= targetDownsample)
			ind--;
		double downsample = downsamples[ind];

		// TODO have a look here https://github.com/BIOP/qupath-extension-biop-omero/issues/17
		/*double[] downsamples = getPreferredDownsamples();
		double downsample = downsamples[downsamples.length - 1];*/

		RegionRequest request = RegionRequest.createInstance(getPath(), downsample, 0, 0, getWidth(), getHeight(), z, t);

		BufferedImage bf = readRegion(request);
		if(isRGB() && bf.getType() == BufferedImage.TYPE_CUSTOM){
			logger.info("Cannot create default thumbnail ; try to get it from OMERO");
			try {
				return imageWrapper.getThumbnail(client.getSimpleClient(), 1024); //256
			} catch (Exception e) {
				OmeroRawTools.readLocalImage(Utils.NO_IMAGE_THUMBNAIL);
			}
		}
		return bf;
	}

	/**
	 * Return the preferred tile width of this {@code ImageServer}.
	 * @return preferredTileWidth
	 */
	public int getPreferredTileWidth() {
		return getMetadata().getPreferredTileWidth();
	}

	/**
	 * Return the preferred tile height of this {@code ImageServer}.
	 * @return preferredTileHeight
	 */
	public int getPreferredTileHeight() {
		return getMetadata().getPreferredTileHeight();
	}


	/**
	 * Return the raw client used for this image server.
	 * @return client
	 */
	public OmeroRawClient getClient() {
		return client;
	}
	
	/**
	 * Return the OMERO ID of the image
	 * @return id
	 */
	public Long getId() {
		return imageID;
	}

	public ImageWrapper getImageWrapper() {return imageWrapper;}
	
	/**
	 * Return the URI host used by this image server
	 * @return host
	 */
	public String getHost() {
		return host;
	}
	
	/**
	 * Return the URI scheme used by this image server
	 * @return scheme
	 */
	public String getScheme() {
		return scheme;
	}
	
	/**
	 * Return the URI port used by this image server
	 * @return port
	 */
	public int getPort() {
		return port;
	}

	@Override
	public int hashCode() {
		return Objects.hash(host, client);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this)
            return true;
		
		if (!(obj instanceof OmeroRawImageServer))
			return false;
		
		return host.equals(((OmeroRawImageServer)obj).getHost()) &&
				client.getUsername().equals(((OmeroRawImageServer)obj).getClient().getUsername());
	}

	@Override
	public void close() throws Exception {
		super.close();
		readerPool.close();
		logger.info("Close OMERO reader for image ID : "+this.getId());
	}

	/**
	 * See below
	 * @return list of path objects
	 */
	@Override
	public Collection<PathObject> readPathObjects() {
		return OmeroRawScripting.getROIs(this, Utils.ALL_USERS, true);
	}

	/**
	 * Retrieve any ROIs created by a certain user stored with this image as annotation objects.
	 * If the user is null, then all ROIs are imported, independently of who creates the ROIs.
	 * ROIs can be made of single or multiple rois. rois can be contained inside ROIs (ex. holes) but should not intersect.
	 * It is also possible to import a set of physically separated ROIs as one geometry ROI.
	 * <br>
	 * ***********************BE CAREFUL****************************<br>
	 * For the z and t in the ImagePlane, if z &lt; 0 and t &lt; 0 (meaning that roi should be present on all the slices/frames),
	 * only the first slice/frame is taken into account (meaning that roi are only visible on the first slice/frame)<br>
	 * ****************************************************************
	 *
	 * @param owner
	 * @return list of path objects
	 * @deprecated use {@link OmeroRawScripting#getROIs(OmeroRawImageServer, String, boolean)} instead
	 */
	@Deprecated
	public Collection<PathObject> readPathObjects(String owner) {
		return OmeroRawScripting.getROIs(this, owner, true);
	}

	static class LocalReaderWrapper {

		private final RawPixelsStorePrx reader;
		private final PixelsWrapper pixelsWrapper;
		int nLevels;
		int[] imageSizeX;
		int[] imageSizeY;


		LocalReaderWrapper(RawPixelsStorePrx reader, PixelsWrapper pixelsWrapper) {
			this.reader = reader;
			this.pixelsWrapper = pixelsWrapper;

			try {

				this.nLevels = reader.getResolutionLevels();
				ResolutionDescription[] levelDescriptions = reader.getResolutionDescriptions();
				imageSizeX = new int[nLevels];
				imageSizeY = new int[nLevels];

				for (int i = 0; i < nLevels; i++) {
					imageSizeX[i] = levelDescriptions[i].sizeX;
					imageSizeY[i] = levelDescriptions[i].sizeY;
				}
			} catch (ServerError e) {
				e.printStackTrace();
			}
		}

		public RawPixelsStorePrx getReader() {
			return reader;
		}

		public PixelsWrapper getPixelsWrapper() { return pixelsWrapper; }
	}

	static class ReaderPool implements AutoCloseable {

		private static final Logger logger = LoggerFactory.getLogger(ReaderPool.class);

		private static final int DEFAULT_TIMEOUT_SECONDS = 60;

		/**
		 * Absolute maximum number of permitted readers (queue capacity)
		 */
		private static final int MAX_QUEUE_CAPACITY = 32;
		private long id;
		private volatile boolean isClosed = false;
		private AtomicInteger totalReaders = new AtomicInteger(0);
		private List<LocalReaderWrapper> additionalReaders = Collections.synchronizedList(new ArrayList<>());
		private ArrayBlockingQueue<LocalReaderWrapper> queue;
		private OmeroRawClient client;
		private LocalReaderWrapper mainReader;
		private ForkJoinTask<?> task;
		private int timeoutSeconds;

		ReaderPool(OmeroRawClient client, long id) {
			this.id = id;
			this.client = client;

			queue = new ArrayBlockingQueue<>(MAX_QUEUE_CAPACITY); // Set a reasonably large capacity (don't want to block when trying to add)
			timeoutSeconds = getTimeoutSeconds();

			// Create the main reader
			long startTime = System.currentTimeMillis();
			mainReader = createReader(id, client);

			long endTime = System.currentTimeMillis();
			logger.info("Reader {} created in {} ms", mainReader, endTime - startTime);

			// Make the main reader available
			queue.add(mainReader);
		}

		/**
		 * Make the timeout adjustable.
		 * See https://github.com/qupath/qupath/issues/1265
		 * @return
		 */
		private int getTimeoutSeconds() {
			return DEFAULT_TIMEOUT_SECONDS;
		}

		LocalReaderWrapper getMainReader() {
			return mainReader;
		}

		OmeroRawClient getClient(){return this.client;}

		private void createAdditionalReader(final Long imageID, OmeroRawClient client) {
			try {
				if (isClosed)
					return;
				logger.debug("Requesting new reader for thread {}", Thread.currentThread());
				var newReader = createReader(imageID, client);
				if (newReader != null) {
					additionalReaders.add(newReader);
					queue.add(newReader);
					logger.info("Created new reader (total={})", additionalReaders.size());
				} else
					logger.warn("New OMERO reader could not be created (returned null)");
			} catch (Exception e) {
				logger.error("Error creating additional readers: " + e.getLocalizedMessage(), e);
			}
		}


		private int getMaxReaders() {
			int max = Runtime.getRuntime().availableProcessors();
			return Math.min(MAX_QUEUE_CAPACITY, Math.max(1, max));
		}


		/**
		 * Create a new {@code IFormatReader}, with memoization if necessary.
		 *
		 * @return the {@code RawPixelsStorePrx}
		 * @throws FormatException
		 * @throws IOException
		 */
		private LocalReaderWrapper createReader(final Long imageId, OmeroRawClient client) {

			int maxReaders = getMaxReaders();
			int nReaders = totalReaders.getAndIncrement();
			if (mainReader != null && nReaders > maxReaders) {
				logger.warn("No new reader will be created (already created {}, max readers {})", nReaders, maxReaders);
				totalReaders.decrementAndGet();
				return null;
			}

			List<OmeroRawClient> clients = OmeroRawClients.getAllClients().stream().filter(c -> !c.equals(client)).collect(Collectors.toList());
			clients.add(0, client);

			long groupId = -1;
			OmeroRawClient currentClient = null;

			for (OmeroRawClient cli : clients) {
				try{
					ImageData img = (ImageData) cli.getSimpleClient().getBrowseFacility().findObject(cli.getSimpleClient().getCtx(), "ImageData", imageId, true);
					groupId = img.getGroupId();
					currentClient = cli;
					break;
				} catch (DSOutOfServiceException | NoSuchElementException | ExecutionException | DSAccessException e) {
					Utils.warnLog(logger, "OMERO - Reader", "Cannot retrieve image '"+imageId+"' from the server '"+cli.getServerURI(), false);
				}
			}

			if(groupId > 0) {
				if(currentClient.getSimpleClient().getCurrentGroupId() != groupId)
					currentClient.switchGroup(groupId);
				this.client = currentClient;

				try{
					ImageWrapper image = currentClient.getSimpleClient().getImage(imageId);
					PixelsWrapper pixelsWrapper = image.getPixels();
					RawPixelsStorePrx rawPixStore = currentClient.getSimpleClient().getGateway().getPixelsStore(currentClient.getSimpleClient().getCtx());
					rawPixStore.setPixelsId(pixelsWrapper.getId(), false);

					// create a local reader grouping the rawPixelStore and the pixelWrapper
					LocalReaderWrapper localWrapper = new LocalReaderWrapper(rawPixStore, pixelsWrapper);

					// add the reader to the objects to clean
					cleanables.add(cleaner.register(this, new ReaderCleaner(Integer.toString(cleanables.size()+1), localWrapper)));
					return localWrapper;
				} catch (Exception e) {
					Utils.errorLog(logger, "OMERO - Reader", "Cannot access the pixel reader for image '"+imageId+"', from the server '"+currentClient.getServerURI(), false);
				}
			} else {
				// user does not have admin rights
				Utils.errorLog(logger, "OMERO - Reader", "The image '"+imageId+"' is not available for you on any connected OMERO servers. \n" +
						"This may be because you do not have the right to access this image.", false);
			}
			return null;
		}

		private LocalReaderWrapper nextQueuedReader() {
			var nextReader = queue.poll();
			if (nextReader != null)
				return nextReader;
			synchronized (this) {
				if (!isClosed && (task == null || task.isDone()) && totalReaders.get() < getMaxReaders()) {
					logger.info("Requesting reader for {}", id);
					task = ForkJoinPool.commonPool().submit(() -> createAdditionalReader(this.id, this.client));
				}
			}
			if (isClosed)
				return null;
			try {
				var reader = queue.poll(timeoutSeconds, TimeUnit.SECONDS);
				// See https://github.com/qupath/qupath/issues/1265
				if (reader == null) {
					logger.warn("Bio-Formats reader request timed out after {} seconds - returning main reader", timeoutSeconds);
					return mainReader;
				} else
					return reader;
			} catch (InterruptedException e) {
				logger.warn("Interrupted exception when awaiting next queued reader: {}", e.getLocalizedMessage());
				return isClosed ? null : mainReader;
			}
		}


		BufferedImage openImage(TileRequest tileRequest, int sizeC, ColorModel colorModel) throws IOException, InterruptedException {
			int level = tileRequest.getLevel();
			int tileX = tileRequest.getTileX();
			int tileY = tileRequest.getTileY();
			int tileWidth = tileRequest.getTileWidth();
			int tileHeight = tileRequest.getTileHeight();
			int z = tileRequest.getZ();
			int t = tileRequest.getT();

			byte[][] bytes = null;
			int effectiveC;
			int length = 0;
			ByteOrder order = ByteOrder.BIG_ENDIAN;
			boolean interleaved = false;
			String pixelType;
			boolean normalizeFloats = false;

			LocalReaderWrapper ipReader = null;
			try {
				ipReader = nextQueuedReader();
				if (ipReader == null) {
					throw new IOException("Reader is null - was the image already closed? " + id);
				}

				// Check if this is non-zero
				if (tileWidth <= 0 || tileHeight <= 0) {
					throw new IOException("Unable to request pixels for region with downsampled size " + tileWidth + " x " + tileHeight);
				}

				synchronized(ipReader) {
					int realLevel = ipReader.nLevels - 1 - level;
					try{
						ipReader.getReader().setResolutionLevel(realLevel);
					}catch(ServerError e){
						throw convertToIOException(e);
					}

					// Recalculate TileWidth and Height in case they exceed the limits of the dataset
					int minX = tileX;
					int maxX = Math.min(tileX + tileWidth, ipReader.imageSizeX[level]);
					int minY = tileY;
					int maxY = Math.min(tileY + tileHeight, ipReader.imageSizeY[level]);
					tileWidth = maxX - minX;
					tileHeight = maxY - minY;

					pixelType = ipReader.getPixelsWrapper().getPixelType();

					// Read bytes for all the required channels
					effectiveC = ipReader.getPixelsWrapper().getSizeC();
					bytes = new byte[effectiveC][];

					for (int c = 0; c < effectiveC; c++) {
						try{
							bytes[c] = ipReader.getReader().getTile(z, c, t, tileX, tileY, tileWidth, tileHeight);
							length = bytes[c].length;
						}catch(ServerError e){
							throw convertToIOException(e);
						}
					}
				}
			} finally {
				queue.put(ipReader);
			}

			// convert byte array to data buffer
			DataBuffer dataBuffer = byteToDataBuffer(bytes, pixelType, length, order, normalizeFloats);
			// create model
			SampleModel sampleModel = createSampleModel(dataBuffer, tileWidth, tileHeight, sizeC, effectiveC, interleaved);
			// create the image
			WritableRaster raster = WritableRaster.createWritableRaster(sampleModel, dataBuffer, null);

			return new BufferedImage(colorModel, raster, false, null);
		}

		private DataBuffer byteToDataBuffer(byte[][] bytes, String pixelType, int length, ByteOrder order, boolean normalizeFloats){
			switch (pixelType) {
				case (PixelsData.UINT8_TYPE):
					return new DataBufferByte(bytes, length);
				case (PixelsData.UINT16_TYPE):
					length /= 2;
					short[][] array = new short[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						ShortBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asShortBuffer();
						array[i] = new short[buffer.limit()];
						buffer.get(array[i]);
					}
					return new DataBufferUShort(array, length);
				case (PixelsData.INT16_TYPE):
					length /= 2;
					short[][] shortArray = new short[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						ShortBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asShortBuffer();
						shortArray[i] = new short[buffer.limit()];
						buffer.get(shortArray[i]);
					}
					return new DataBufferShort(shortArray, length);
				case (PixelsData.INT32_TYPE):
					length /= 4;
					int[][] intArray = new int[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						IntBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asIntBuffer();
						intArray[i] = new int[buffer.limit()];
						buffer.get(intArray[i]);
					}
					return new DataBufferInt(intArray, length);
				case (PixelsData.FLOAT_TYPE):
					length /= 4;
					float[][] floatArray = new float[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						FloatBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asFloatBuffer();
						floatArray[i] = new float[buffer.limit()];
						buffer.get(floatArray[i]);
						if (normalizeFloats)
							floatArray[i] = DataTools.normalizeFloats(floatArray[i]);
					}
					return new DataBufferFloat(floatArray, length);
				case (PixelsData.DOUBLE_TYPE):
					length /= 8;
					double[][] doubleArray = new double[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						DoubleBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asDoubleBuffer();
						doubleArray[i] = new double[buffer.limit()];
						buffer.get(doubleArray[i]);
						if (normalizeFloats)
							doubleArray[i] = DataTools.normalizeDoubles(doubleArray[i]);
					}
					return new DataBufferDouble(doubleArray, length);
				default:
					throw new UnsupportedOperationException("Unsupported pixel type " + pixelType);
			}
		}

		private SampleModel createSampleModel(DataBuffer dataBuffer, int tileWidth, int tileHeight, int sizeC, int effectiveC, boolean interleaved){
			if (effectiveC == 1 && sizeC > 1) {
				// Handle channels stored in the same plane
				int[] offsets = new int[sizeC];
				if (interleaved) {
					for (int b = 0; b < sizeC; b++)
						offsets[b] = b;
					return new PixelInterleavedSampleModel(dataBuffer.getDataType(), tileWidth, tileHeight, sizeC, sizeC*tileWidth, offsets);
				} else {
					for (int b = 0; b < sizeC; b++)
						offsets[b] = b * tileWidth * tileHeight;
					return new ComponentSampleModel(dataBuffer.getDataType(), tileWidth, tileHeight, 1, tileWidth, offsets);
				}
			} else {
				// Merge channels on different planes
				return new BandedSampleModel(dataBuffer.getDataType(), tileWidth, tileHeight, sizeC);
			}
		}

		/**
		 * Ensure a throwable is an IOException.
		 * This gives the opportunity to include more human-readable messages for common errors.
		 * @param t
		 * @return
		 */
		private static IOException convertToIOException(Throwable t) {
			if (GeneralTools.isMac()) {
				String message = t.getMessage();
				if (message != null) {
					if (message.contains("ome.jxrlib.JXRJNI")) {
						return new IOException("OMERO does not support JPEG-XR on Apple Silicon: " + t.getMessage(), t);
					}
					if (message.contains("org.libjpegturbo.turbojpeg.TJDecompressor")) {
						return new IOException("OMERO does not currently support libjpeg-turbo on Apple Silicon", t);
					}
				}
			}
			if (t instanceof IOException e)
				return e;
			return new IOException(t);
		}


		@Override
		public void close() throws Exception {
			isClosed = true;
			logger.info("calling close");
			if (task != null && !task.isDone())
				task.cancel(true);
			for (var c : cleanables) {
				try {
					c.clean();
				} catch (Exception e) {
					logger.error("Exception during cleanup: " + e.getLocalizedMessage());
					logger.debug(e.getLocalizedMessage(), e);
				}
			}
			// Allow the queue to be garbage collected - clearing could result in a queue.poll()
			// lingering far too long
//			queue.clear();
		}



		private static Cleaner cleaner = Cleaner.create();
		private List<Cleaner.Cleanable> cleanables = new ArrayList<>();


		/**
		 * Helper class that helps ensure readers are closed when a reader pool is no longer reachable.
		 */
		static class ReaderCleaner implements Runnable {

			private String name;
			private LocalReaderWrapper reader;

			ReaderCleaner(String name, LocalReaderWrapper reader) {
				this.name = name;
				this.reader = reader;
			}

			@Override
			public void run() {
				try {
					logger.info("Cleaner " + name + " called for " + reader + " (" + reader.getReader().getPixelsId() + ")");
					this.reader.getReader().close();
				} catch (ServerError e) {
					logger.warn("Error when calling cleaner for " + name, e);
				}
			}

		}


	}
}