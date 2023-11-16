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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClients;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClientsCommand;
import qupath.ext.biop.servers.omero.raw.command.OmeroRawImportAnnotationObjectsCommand;
import qupath.ext.biop.servers.omero.raw.command.OmeroRawImportChannelSettingsCommand;
import qupath.ext.biop.servers.omero.raw.command.OmeroRawImportMetadataCommand;
import qupath.ext.biop.servers.omero.raw.command.OmeroRawWriteAnnotationObjectsCommand;
import qupath.ext.biop.servers.omero.raw.command.OmeroRawWriteChannelSettingsCommand;
import qupath.ext.biop.servers.omero.raw.command.OmeroRawWriteMetadataCommand;
import qupath.lib.common.Version;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.gui.actions.ActionTools;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;
import qupath.fx.utils.GridPaneUtils;

/**
 * Extension to access images hosted on OMERO.
 */
public class OmeroRawExtension implements QuPathExtension, GitHubProject {
	
	private final static Logger logger = LoggerFactory.getLogger(OmeroRawExtension.class);

	/**
	 * To handle the different stages of browsers (only allow one per OMERO server)
	 */
	private static final Map<OmeroRawClient, OmeroRawImageServerBrowserCommand> rawBrowsers = new HashMap<>();

	private static final String defaultOmeroServerFilename = "DefaultOmeroServer.txt";

	private static boolean alreadyInstalled = false;
	
	@Override
	public void installExtension(QuPathGUI qupath) {
		if (alreadyInstalled)
			return;
		
		logger.debug("Installing OMERO extension");
		
		alreadyInstalled = true;

		// for OMERO raw extension
		var actionRawClients = ActionTools.createAction(new OmeroRawClientsCommand(qupath), "Manage server connections");
		var actionRawSendAnnotationObjects = ActionTools.createAction(new OmeroRawWriteAnnotationObjectsCommand(qupath), "Annotations");
		var actionRawSendMetadataObjects = ActionTools.createAction(new OmeroRawWriteMetadataCommand(qupath), "Metadata");
		var actionRawSendDisplaySettingsObjects = ActionTools.createAction(new OmeroRawWriteChannelSettingsCommand(qupath), "Image & channels settings");
		var actionRawImportAnnotationObjects = ActionTools.createAction(new OmeroRawImportAnnotationObjectsCommand(qupath), "Annotations");
		var actionRawImportMetadataObjects = ActionTools.createAction(new OmeroRawImportMetadataCommand(qupath), "Metadata");
		var actionRawImportDisplaySettingsObjects = ActionTools.createAction(new OmeroRawImportChannelSettingsCommand(qupath), "Channels settings");
		Menu browseRawServerMenu = new Menu("Browse server...");

//		actionRawClients.disabledProperty().bind(qupath.projectProperty().isNull());
//		browseRawServerMenu.disableProperty().bind(qupath.projectProperty().isNull());
		actionRawSendAnnotationObjects.disabledProperty().bind(qupath.imageDataProperty().isNull());
		actionRawImportAnnotationObjects.disabledProperty().bind(qupath.imageDataProperty().isNull());
		actionRawSendMetadataObjects.disabledProperty().bind(qupath.imageDataProperty().isNull());
		actionRawImportMetadataObjects.disabledProperty().bind(qupath.imageDataProperty().isNull());
		actionRawSendDisplaySettingsObjects.disabledProperty().bind(qupath.imageDataProperty().isNull());
		actionRawImportDisplaySettingsObjects.disabledProperty().bind(qupath.imageDataProperty().isNull());
		MenuTools.addMenuItems(qupath.getMenu("Extensions", false),
				MenuTools.createMenu("OMERO-RAW",
						browseRawServerMenu,
						actionRawClients,
						null,
						MenuTools.createMenu("Send to OMERO", actionRawSendAnnotationObjects, actionRawSendMetadataObjects, actionRawSendDisplaySettingsObjects),
						MenuTools.createMenu("Import from OMERO", actionRawImportAnnotationObjects, actionRawImportMetadataObjects, actionRawImportDisplaySettingsObjects)
				)
		);

		createRawServerListMenu(qupath, browseRawServerMenu);
	}
	

	@Override
	public String getName() {
		return "OMERO BIOP extension";
	}

	@Override
	public String getDescription() {
		return "Adds the ability to browse OMERO servers and open images hosted on OMERO servers.";
	}


	private static Menu createRawServerListMenu(QuPathGUI qupath, Menu browseServerMenu) {
		EventHandler<Event> validationHandler = e -> {
			browseServerMenu.getItems().clear();

			// Get all active servers
			var activeServers = OmeroRawClients.getAllClients();

			// Populate the menu with each unique active servers
			for (var client: activeServers) {
				if (client == null)
					continue;
				MenuItem item = new MenuItem(client.getServerURI() + "...");
				item.setOnAction(e2 -> {
					var browser = rawBrowsers.get(client);
					if (browser == null || browser.getStage() == null) {
						browser = new OmeroRawImageServerBrowserCommand(qupath, client);
						rawBrowsers.put(client, browser);
						browser.run();
					} else
						browser.getStage().requestFocus();
				});
				browseServerMenu.getItems().add(item);
			}

			// Create 'New server...' MenuItem
			MenuItem customServerItem = new MenuItem("New server...");
			customServerItem.setOnAction(e2 -> {
				// get default server
				String defaultOmeroServer = getDefaultOmeroServer();

				GridPane gp = new GridPane();
				gp.setVgap(5.0);
				TextField tf = new TextField(defaultOmeroServer==null ? "": defaultOmeroServer);
				tf.setPrefWidth(400);
				GridPaneUtils.addGridRow(gp, 0, 0, "Enter OMERO URL", new Label("Enter an OMERO server URL to browse (e.g. http://idr.openmicroscopy.org/):"));
				GridPaneUtils.addGridRow(gp, 1, 0, "Enter OMERO URL", tf, tf);
				var confirm = Dialogs.showConfirmDialog("Enter OMERO URL", gp);
				if (!confirm)
					return;

				var path = tf.getText();
				if (path == null || path.isEmpty())
					return;
				try {
					if (!path.startsWith("http:") && !path.startsWith("https:"))
						throw new IOException("The input URL must contain a scheme (e.g. \"https://\")!");

					// Make the path a URI
					URI uri = new URI(path);

					// Clean the URI (in case it's a full path)
					URI uriServer = OmeroRawTools.getServerURI(uri);

					if (uriServer == null)
						throw new MalformedURLException("Could not parse server from " + uri);

					// create the txt containing the default omero server
					if(defaultOmeroServer == null)
						createOmeroDefaultServerFile(uriServer.toString());

					// Check if client exist and if browser is already opened
					var client = OmeroRawClients.getClientFromServerURI(uriServer);
					if (client == null)
						client = OmeroRawClients.createClientAndLogin(uriServer);

					if (client == null)
						throw new IOException("Could not parse server from " + uri);

					var browser = rawBrowsers.get(client);
					if (browser == null || browser.getStage() == null) {
						// Create new browser
						browser = new OmeroRawImageServerBrowserCommand(qupath, client);
						rawBrowsers.put(client, browser);
						browser.run();
					} else	// Request focus for already-existing browser
						browser.getStage().requestFocus();

				} catch (FileNotFoundException ex) {
					Dialogs.showErrorMessage("OMERO-RAW server", String.format("An error occured when trying to reach %s: %s\"", path, ex.getLocalizedMessage()));
				} catch (IOException | URISyntaxException ex) {
					Dialogs.showErrorMessage("OMERO-RAW server", ex.getLocalizedMessage());
					return;
				}
			});
			MenuTools.addMenuItems(browseServerMenu, null, customServerItem);
		};

		// Ensure the menu is populated (every time the parent menu is opened)
		browseServerMenu.getParentMenu().setOnShowing(validationHandler);
		return browseServerMenu;
	}

	/**
	 * read the txt file where the omero server is stored. If there is no file, or if the file is corrupted,
	 * then it returns an empty string.
	 *
	 * @return
	 */
	private static String getDefaultOmeroServer(){
		Path extensionPath = UserDirectoryManager.getInstance().getExtensionsPath();
		if(extensionPath == null)
			return null;

		File dir  = extensionPath.toFile();
		File[] fileList = dir.listFiles();

		if(fileList == null)
			return null;

		String omeroServer = null;
		for(File item : fileList){
			if(item.isFile() && item.getName().equals(defaultOmeroServerFilename))
			{
				try (BufferedReader br = new BufferedReader(new FileReader(item))){
					String st = br.readLine();
					if (st != null)
						omeroServer = st;
				}catch (IOException e){
					Dialogs.showWarningNotification("Load OMERO User preferences","Unable to find your default OMERO server");
					logger.error(String.valueOf(e));
					logger.error(OmeroRawTools.getErrorStackTraceAsString(e));
				}
				break;
			}
		}

		return omeroServer;
	}

	/**
	 * create a txt file with the omero server entered by the user.
	 * This is to avoid typing it each time you want to connect to OMERO server
	 *
	 * @param omeroDefaultServer
	 */
	private static void createOmeroDefaultServerFile(String omeroDefaultServer) {
		Path extensionPath = UserDirectoryManager.getInstance().getExtensionsPath();
		if(extensionPath == null || !extensionPath.toFile().exists()) return;

		try (FileWriter myWriter = new FileWriter(extensionPath + File.separator + defaultOmeroServerFilename)){
			myWriter.write(omeroDefaultServer);
		} catch (IOException e) {
			Dialogs.showWarningNotification("Create default Omero server file","An error occurred during File creation");
			logger.error(String.valueOf(e));
			logger.error(OmeroRawTools.getErrorStackTraceAsString(e));
		}
	}

	/**
	 * Return map of currently opened browsers.
	 *
	 * @return rawBrowsers
	 */
	public static Map<OmeroRawClient, OmeroRawImageServerBrowserCommand> getOpenedRawBrowsers() {
		return rawBrowsers;
	}

	@Override
	public GitHubRepo getRepository() {
		return GitHubRepo.create(getName(), "biop", "qupath-extension-biop-omero");
	}

	
	@Override
	public Version getQuPathVersion() {
		return QuPathExtension.super.getQuPathVersion();
	}
}
