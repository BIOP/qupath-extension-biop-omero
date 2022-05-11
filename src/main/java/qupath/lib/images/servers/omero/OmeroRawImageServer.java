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

package qupath.lib.images.servers.omero;

import loci.common.DataTools;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.formats.FormatException;
import loci.formats.MetadataTools;
import loci.formats.gui.AWTImageTools;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataStore;
import omero.ServerError;
import omero.api.RawPixelsStorePrx;
import omero.api.ResolutionDescription;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.MetadataFacility;
import omero.gateway.model.ChannelData;
import omero.gateway.model.ImageData;
import omero.gateway.model.PixelsData;
import omero.model.ChannelBinding;
import omero.model.Length;
import omero.model.RenderingDef;
import omero.model.enums.UnitsLength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.color.ColorModelFactory;
import qupath.lib.common.ColorTools;
import qupath.lib.images.servers.*;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;

import javax.imageio.ImageIO;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.net.URI;
import java.nio.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ImageServer that reads pixels using the OMERO web API.
 * <p>
 * Note that this does not provide access to the raw data, but rather RGB tiles only in the manner of a web viewer. 
 * Consequently, only RGB images are supported and some small changes in pixel values can be expected due to compression.
 * 
 * @author Pete Bankhead
 *
 */
public class OmeroRawImageServer extends AbstractTileableImageServer {

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
	private final OmeroRawClient client;

	/**
	 * Main reader for metadata and all that jazz
	 */
	private OmeroReaderManager.LocalReaderWrapper readerWrapper;

	/**
	 * Manager to help keep multithreading under control.
	 */
	private static OmeroReaderManager manager = new OmeroRawImageServer.OmeroReaderManager();

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
	OmeroRawImageServer(URI uri, OmeroRawClient client, String...args) throws IOException, ServiceException, ServerError, DSOutOfServiceException, DependencyException, ExecutionException, FormatException, DSAccessException {
		super();


		this.uri = uri;
		this.scheme = uri.getScheme();
		this.host = uri.getHost();
		this.port = uri.getPort();
		this.client = client;
		this.originalMetadata = buildMetadata();
		// Args are stored in the JSON - passwords and usernames must not be included!
		// Do an extra check to ensure someone hasn't accidentally passed one
		var invalid = Arrays.asList("--password", "-p", "-u", "--username", "-password");
		for (int i = 0; i < args.length; i++) {
			String arg = args[i].toLowerCase().strip();
			if (invalid.contains(arg)) {
				throw new IllegalArgumentException("Cannot build server with arg " + arg);
			}
		}
		this.args = args;
		
		// Add URI to the client's list of URIs
		client.addURI(uri);
	}
	
	protected ImageServerMetadata buildMetadata() throws IOException, ServerError, ServiceException, DSOutOfServiceException, DependencyException, ExecutionException, FormatException, DSAccessException {

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
		readerWrapper = manager.getPrimaryReaderWrapper(imageID, client);
		RawPixelsStorePrx reader = readerWrapper.getReader();
		PixelsData meta = readerWrapper.getPixelsData();

		// There is just one series per image ID
		synchronized (reader) {
			String name = meta.getImage().getName();

			long sizeX = meta.getSizeX();
			long sizeY = meta.getSizeY();

			int nResolutions = reader.getResolutionLevels();
			for (int r = 1; r < nResolutions; r++) {
				int sizeXR = reader.getResolutionDescriptions()[r].sizeX;
				int sizeYR = reader.getResolutionDescriptions()[r].sizeY;
				if (sizeXR <= 0 || sizeYR <= 0 || sizeXR > sizeX || sizeYR > sizeY)
					throw new IllegalArgumentException("Resolution " + r + " size " + sizeXR + " x " + sizeYR + " invalid!");
			}

			// If we have more than one image, ensure that we have the image name correctly encoded in the path
			// Need the context here for now TODO: Make it cleaner to get data
			// Try getting the magnification
			try {
				MetadataFacility metaFacility = client.getGateway().getFacility(MetadataFacility.class);

				Double magnificationObject = metaFacility.getImageAcquisitionData(client.getContext(), imageID).getObjective().getNominalMagnification();

				if (magnificationObject == null) {
					logger.warn("Nominal objective magnification missing for {}", imageID);
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
			List<ChannelData> channelMetadata = client.getGateway().getFacility(MetadataFacility.class).getChannelData(client.getContext(), imageID);
			RenderingDef renderingSettings = client.getGateway().getRenderingSettingsService(client.getContext()).getRenderingSettings(reader.getPixelsId());

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
						channelColor = ColorTools.makeRGB(255, 255, 255);
					else
						channelColor = ImageChannel.getDefaultChannelColor(c);
				}

				if (channelName == null || channelName.isBlank())
					channelName = "Channel " + (c + 1);
				channels.add(ImageChannel.getInstance(channelName, channelColor));
			}
			// Update RGB status if needed - sometimes we might really have an RGB image, but the Bio-Formats flag doesn't show this -
			// and we want to take advantage of the optimizations where we can
			if (nChannels == 3 &&
					pixelType == PixelType.UINT8 &&
					channels.equals(ImageChannel.getDefaultRGBChannels())
			) {
				isRGB = true;
				colorModel = ColorModel.getRGBdefault();
			} else {
				colorModel = ColorModelFactory.createColorModel(pixelType, channels);
			}

			// Try parsing pixel sizes in micrometers
			double[] timepoints;
			try {
				Length xSize = meta.getPixelSizeX(UnitsLength.MICROMETER);
				Length ySize = meta.getPixelSizeY(UnitsLength.MICROMETER);
				if (xSize != null && ySize != null) {
					pixelWidth = xSize.getValue();
					pixelHeight = ySize.getValue();
				} else {
					pixelWidth = Double.NaN;
					pixelHeight = Double.NaN;
				}
				// If we have multiple z-slices, parse the spacing
				if (nZSlices > 1) {
					Length zSize = meta.getPixelSizeZ(UnitsLength.MICROMETER);
					if (zSize != null)
						zSpacing = zSize.getValue();
					else
						zSpacing = Double.NaN;
				}

/*
                // TODO: Check the Bioformats TimeStamps
                if (nTimepoints > 1) {
                    int lastTimepoint = -1;
                    int count = 0;
                    timepoints = new double[nTimepoints];
                    logger.debug("Number of Timepoints: " + reader.getTimepointSize());
                    for (int plane = 0; plane < reader.getTimepointSize(); plane++) {
                        int timePoint = meta.getPlaneTheT(series, plane).getValue();
                        logger.debug("Checking " + timePoint);
                        if (timePoint != lastTimepoint) {
                            timepoints[count] = meta.getPlaneDeltaT(series, plane).value(UNITS.SECOND).doubleValue();
                            logger.debug(String.format("Timepoint %d: %.3f seconds", count, timepoints[count]));
                            lastTimepoint = timePoint;
                            count++;
                        }
                    }
                    timeUnit = TimeUnit.SECONDS;
                } else {
                    timepoints = new double[0];
                }

 */             // TODO get timepoints
				timepoints = new double[0];
			} catch (Exception e) {
				logger.error("Error parsing metadata", e);
				pixelWidth = Double.NaN;
				pixelHeight = Double.NaN;
				zSpacing = Double.NaN;
				timepoints = null;
				timeUnit = null;
			}

			// Loop through the series & determine downsamples
			ResolutionDescription[] resDescriptions = reader.getResolutionDescriptions();
			var resolutionBuilder = new ImageServerMetadata.ImageResolutionLevel.Builder(width, height)
					.addFullResolutionLevel();

			// I have seen czi files where the resolutions are not read correctly & this results in an IndexOutOfBoundsException
			for (int i = 1; i < nResolutions; i++) {
				try {
					int w = resDescriptions[i].sizeX;
					int h = resDescriptions[i].sizeY;
					if (w <= 0 || h <= 0) {
						logger.warn("Invalid resolution size {} x {}! Will skip this level, but something seems wrong...", w, h);
						continue;
					}

					resolutionBuilder.addLevel(w, h);
					//logger.warn("found width:{}, found height:{}", w, h);

				} catch (Exception e) {

					logger.warn("Error attempting to extract resolution " + i + " for " + meta.getImage().getName(), e);
					break;
				}
			}

			// Generate a suitable name for this image
			String imageName = meta.getImage().getName();

			// Set metadata
			String path = createID();
			ImageServerMetadata.Builder builder = new ImageServerMetadata.Builder(
					getClass(), path, width, height).
					name(imageName).
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


			long endTime = System.currentTimeMillis();
			logger.debug(String.format("Initialization time: %d ms", endTime - startTime));


			return builder.build();
		}

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

		int level = request.getLevel();

		int tileX = request.getTileX();
		int tileY = request.getTileY();
		int tileWidth = request.getTileWidth();
		int tileHeight = request.getTileHeight();
		int z = request.getZ();
		int t = request.getT();

		RawPixelsStorePrx rawPixelsStore = readerWrapper.getReader();

		if (rawPixelsStore == null) {
			throw new IOException("Reader is null - was the image already closed? " + imageID);
		}

		// Check if this is non-zero
		if (tileWidth <= 0 || tileHeight <= 0) {
			throw new IOException("Unable to request pixels for region with downsampled size " + tileWidth + " x " + tileHeight);
		}

		byte[][] bytes = null;
		int effectiveC;
		int sizeC = nChannels();
		int length = 0;
		ByteOrder order = ByteOrder.BIG_ENDIAN;
		boolean interleaved;
		String pixelType;
		boolean normalizeFloats = false;
		try {
			synchronized(rawPixelsStore) {
				//   synchronized(OmeroRawServer.class) {

				//BrowseFacility browse = gateway.getFacility(BrowseFacility.class);
				// ImageData image = browse.getImage(context, imageID);
				// PixelsData pixelData = image.getDefaultPixels();

				// rawPixelsStore = OmeroRawServer.gateway.getPixelsStore(context);
				// rawPixelsStore.setPixelsId(pixelData.getId(), false);


				int realLevel = readerWrapper.nLevels - 1 - level;
				rawPixelsStore.setResolutionLevel(realLevel);

				// Recalculate TileWidth and Height in case they exceed the limits of the dataset

				int minX = tileX;

				int maxX = Math.min(tileX + tileWidth, readerWrapper.imageSizeX[level]);

				int minY = tileY;
				int maxY = Math.min(tileY + tileHeight, readerWrapper.imageSizeY[level]);
				tileWidth = maxX - minX;
				tileHeight = maxY - minY;

				order = ByteOrder.BIG_ENDIAN; // ByteOrder.LITTLE_ENDIAN
				interleaved = false;
				pixelType = readerWrapper.getPixelsData().getPixelType();
				normalizeFloats = false;

				// Single-channel
				if (nChannels() == 1) {
					// Read the image - or at least the first channel

					byte[] bytesSimple = rawPixelsStore.getTile(z, 0, t, tileX, tileY, tileWidth, tileHeight);
					return AWTImageTools.makeImage(bytesSimple, tileWidth, tileHeight, 1, interleaved, 1, false, false, false );


				}
				// Read bytes for all the required channels
				effectiveC = readerWrapper.getPixelsData().getSizeC();
				//effectiveC = 1; // TODO find a way to get the channel size
				bytes = new byte[effectiveC][];

				for (int c = 0; c < effectiveC; c++) {
					bytes[c] = rawPixelsStore.getTile(z, c, t, tileX, tileY, tileWidth, tileHeight);
					length = bytes[c].length;
				}

			}

			DataBuffer dataBuffer;
			switch (pixelType) {
				case (PixelsData.UINT8_TYPE):
					dataBuffer = new DataBufferByte(bytes, length);
					break;
				case (PixelsData.UINT16_TYPE):
					length /= 2;
					short[][] array = new short[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						ShortBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asShortBuffer();
						array[i] = new short[buffer.limit()];
						buffer.get(array[i]);
					}
					dataBuffer = new DataBufferUShort(array, length);
					break;
				case (PixelsData.INT16_TYPE):
					length /= 2;
					short[][] shortArray = new short[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						ShortBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asShortBuffer();
						shortArray[i] = new short[buffer.limit()];
						buffer.get(shortArray[i]);
					}
					dataBuffer = new DataBufferShort(shortArray, length);
					break;
				case (PixelsData.INT32_TYPE):
					length /= 4;
					int[][] intArray = new int[bytes.length][length];
					for (int i = 0; i < bytes.length; i++) {
						IntBuffer buffer = ByteBuffer.wrap(bytes[i]).order(order).asIntBuffer();
						intArray[i] = new int[buffer.limit()];
						buffer.get(intArray[i]);
					}
					dataBuffer = new DataBufferInt(intArray, length);
					break;
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
					dataBuffer = new DataBufferFloat(floatArray, length);
					break;
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
					dataBuffer = new DataBufferDouble(doubleArray, length);
					break;
				default:
					throw new UnsupportedOperationException("Unsupported pixel type " + pixelType);
			}

			SampleModel sampleModel;

			if (effectiveC == 1 && sizeC > 1) {
				// Handle channels stored in the same plane
				int[] offsets = new int[sizeC];
				if (interleaved) {
					for (int b = 0; b < sizeC; b++)
						offsets[b] = b;
					sampleModel = new PixelInterleavedSampleModel(dataBuffer.getDataType(), tileWidth, tileHeight, sizeC, sizeC*tileWidth, offsets);
				} else {
					for (int b = 0; b < sizeC; b++)
						offsets[b] = b * tileWidth * tileHeight;
					sampleModel = new ComponentSampleModel(dataBuffer.getDataType(), tileWidth, tileHeight, 1, tileWidth, offsets);
				}
			} else {
				// Merge channels on different planes
				sampleModel = new BandedSampleModel(dataBuffer.getDataType(), tileWidth, tileHeight, sizeC);
				//sampleModel = new Sample
			}

			WritableRaster raster = WritableRaster.createWritableRaster(sampleModel, dataBuffer, null);
			return new BufferedImage(colorModel, raster, false, null);

		} catch (Exception | Error e) {
			e.printStackTrace();
			return null;
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
	 * Return the web client used for this image server.
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



	/**
		 * Helper class to manage multiple Bio-Formats image readers.
		 * <p>
		 * This has two purposes:
		 * <ol>
		 *   <li>To construct IFormatReaders in a standardized way (e.g. with/without memoization).</li>
		 *   <li>To track the size of any memoization files for particular readers.</li>
		 *   <li>To allow BioFormatsImageServers to request separate Bio-Formats image readers for different threads.</li>
		 * </ol>
		 * The memoization file size can be relevant because some readers are very memory-hungry, and may need to be created rarely.
		 * On the other side, some readers are very lightweight - and having multiple such readers active at a time can help rapidly
		 * respond to tile requests.
		 * <p>
		 * It's up to any consumers to ensure that heavyweight readers aren't called for each thread. Additionally, each thread
		 * will have only one reader active at any time. This should be explicitly closed by the calling thread if it knows
		 * the reader will not be used again, but in practice this is often not the case and therefore a Cleaner is registered to
		 * provide some additional support for when a thread is no longer reachable.
		 * <p>
		 * Note that this does mean one should be sparing when creating threads, and not keep them around too long.
		 */
		static class OmeroReaderManager {

			private static Cleaner cleaner = Cleaner.create();

			/**
			 * Map of reads for each calling thread.  Care should be taking by the calling code to ensure requests are only made for 'lightweight' readers to avoid memory problems.
			 */
			private static ThreadLocal<LocalReaderWrapper> localReader = new ThreadLocal<>();

			/**
			 * Map of memoization file sizes.
			 */
			private static Map<String, Long> memoizationSizeMap = new HashMap<>();

			/**
			 * Temporary directory for storing memoization files
			 */
			private static File dirMemoTemp = null;

			/**
			 * A set of primary readers, to avoid needing to regenerate these for all servers.
			 */
			private static Set<LocalReaderWrapper> primaryReaders = Collections.newSetFromMap(new WeakHashMap<>());

			/**
			 * Set of created temp memo files
			 */
			private static Set<File> tempMemoFiles = new HashSet<>();

			/**
			 * Request a IFormatReader for a specified path that is unique for the calling thread.
			 * <p>
			 * Note that the state of the reader is not specified; setSeries should be called before use.
			 *
			 * @return
			 * @throws DependencyException
			 * @throws ServiceException
			 * @throws FormatException
			 * @throws IOException
			 */
			public synchronized RawPixelsStorePrx getReaderForThread( final Long pixelsId,  OmeroRawClient client ) throws FormatException, IOException, ServerError, DSOutOfServiceException, ExecutionException, DSAccessException {

				LocalReaderWrapper wrapper = localReader.get();

				// Check if we already have the correct reader
				RawPixelsStorePrx reader = wrapper == null ? null : wrapper.getReader();
				if (reader != null) {
					if (pixelsId.equals(wrapper.reader.getPixelsId()))
						return reader;
					else {
						logger.warn("Closing reader {}", reader);
						reader.close();
					}
				}

				// Create a new reader
				wrapper = createReader( pixelsId, null, client );

				// Store wrapped reference with associated cleaner
				localReader.set(wrapper);

				return reader;
			}


			private LocalReaderWrapper wrapReader(RawPixelsStorePrx reader, PixelsData pixelsData) {
				LocalReaderWrapper wrapper = new LocalReaderWrapper(reader, pixelsData);
				logger.debug("Constructing reader for {}", Thread.currentThread());
				cleaner.register(
						wrapper,
						new ReaderCleaner(Thread.currentThread().toString(), reader));
				return wrapper;
			}


			/**
			 * Request a IFormatReader for the specified path.
			 * <p>
			 * This reader will have OME metadata populated in an accessible form, but will *not* be unique for the calling thread.
			 * Therefore care needs to be taken with regard to synchronization.
			 * <p>
			 * Note that the state of the reader is not specified; setSeries should be called before use.
			 *
			 * @param pixelsID
			 * @return
			 * @throws DependencyException
			 * @throws ServiceException
			 * @throws FormatException
			 * @throws IOException
			 */
			synchronized LocalReaderWrapper createPrimaryReader(final Long pixelsID, IMetadata metadata, OmeroRawClient client) throws FormatException, IOException, ServerError, DSOutOfServiceException, ExecutionException, DSAccessException {
				return createReader(pixelsID, metadata == null ? MetadataTools.createOMEXMLMetadata() : metadata, client);
			}


			/**
			 * Get a wrapper for the primary reader for a particular path. This can be reused across ImageServers, but
			 * one must be careful to synchronize the actual use of the reader.
			 * @param pixelsID
			 * @return
			 * @throws DependencyException
			 * @throws ServiceException
			 * @throws FormatException
			 * @throws IOException
			 */
			synchronized LocalReaderWrapper getPrimaryReaderWrapper(final Long pixelsID, OmeroRawClient client) throws DependencyException, ServiceException, FormatException, IOException, ServerError, DSOutOfServiceException, ExecutionException, DSAccessException {
				for (LocalReaderWrapper wrapper : primaryReaders) {
					if (pixelsID.equals(wrapper.getReader().getPixelsId()))
						return wrapper;
				}
				LocalReaderWrapper reader = createPrimaryReader( pixelsID, null, client );
				primaryReaders.add(reader);
				return reader;
			}


			/**
			 * Create a new {@code}, with memoization if necessary.
			 *
			 * @param imageID 		file path for the image.
			 * @param store 	optional MetadataStore; this will be set in the reader if needed.
			 * @return the {@code IFormatReader}
			 * @throws FormatException
			 * @throws IOException
			 */
			private synchronized LocalReaderWrapper createReader(final Long imageID, final MetadataStore store, OmeroRawClient client) throws FormatException, IOException, ServerError, DSOutOfServiceException, ExecutionException, DSAccessException {
				BrowseFacility browse = client.getGateway().getFacility(BrowseFacility.class);

				ImageData image = browse.getImage(client.getContext(), imageID);
				PixelsData pixelData = image.getDefaultPixels();
				RawPixelsStorePrx rawPixStore = client.getGateway().getPixelsStore(client.getContext());
				rawPixStore.setPixelsId(pixelData.getId(), false);

				LocalReaderWrapper readerWrapper = new LocalReaderWrapper(rawPixStore, pixelData);
				return readerWrapper;
			}

			/**
			 * Simple wrapper for a reader to help with cleanup.
			 */
			static class LocalReaderWrapper {

				private RawPixelsStorePrx reader;
				private PixelsData pixelsData;
				int nLevels;
				int[] imageSizeX;
				int[] imageSizeY;
				private Map<String, String> readerOptions;

				LocalReaderWrapper(RawPixelsStorePrx reader, PixelsData pixelsData) {
					this.reader = reader;
					this.pixelsData = pixelsData;
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

					this.readerOptions = readerOptions == null || readerOptions.isEmpty() ? Collections.emptyMap() :
							new LinkedHashMap<>(readerOptions);
				}

				public RawPixelsStorePrx getReader() {
					return reader;
				}

				public PixelsData getPixelsData() { return pixelsData; }

				public boolean argsMatch(Map<String, String> readerOptions) {
					return this.readerOptions.equals(readerOptions);
				}

			}

			/**
			 * Helper class that helps ensure readers are closed when a thread is no longer reachable.
			 */
			static class ReaderCleaner implements Runnable {

				private String name;
				private RawPixelsStorePrx reader;

				ReaderCleaner(String name, RawPixelsStorePrx reader) {
					this.name = name;
					this.reader = reader;
				}

				@Override
				public void run() {
					logger.debug("Cleaner " + name + " called for " + reader + " (" + reader.toString() + ")");
					try {
						this.reader.close();
					} catch (ServerError e) {
						logger.warn("Error when calling cleaner for " + name, e);
					}
				}
			}
		}
}