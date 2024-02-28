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

package qupath.ext.biop.servers.omero.raw.client;

import fr.igred.omero.Client;
import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.meta.GroupWrapper;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSOutOfServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.OmeroRawExtension;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.utils.Utils;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;

import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


/**
 * Class representing an OMERO Raw Client. This class takes care of
 * logging in, keeping the connection alive and logging out.
 *
 * @author Olivier Burri and Rémy Dornier
 */
public class OmeroRawClient {
    final private static Logger logger = LoggerFactory.getLogger(OmeroRawClient.class);

    private final IntegerProperty port;
    private final BooleanProperty isAdminUser;
    private final Client simpleClient;

    /**
     * default username appearing when log-in on OMERO
     */
    private static StringProperty defaultUsername;

    /**
     * List of all URIs supported by this client.
     */
    private final ObservableList<URI> uris = FXCollections.observableArrayList();

    /**
     * 'Clean' URI representing the server's URI (<b>not</b> its images). <p> See {@link OmeroRawTools#getServerURI(URI)}.
     */
    private final URI serverURI;

    /**
     * The username might be empty (public), and might also change (user switching account)
     */
    private final StringProperty username;

    /**
     * Logged in property (modified by login/loggedIn/logout/timer)
     */
    private final BooleanProperty loggedIn;

   public static OmeroRawClient create(URI serverURI) throws MalformedURLException, URISyntaxException {
        // Clean server URI (filter out wrong URIs and get rid of unnecessary characters)
        var cleanServerURI = new URL(serverURI.getScheme(), serverURI.getHost(), serverURI.getPort(), "").toURI();

        // Create OmeroRawClient with the serverURI
        return new OmeroRawClient(cleanServerURI);
    }

    private OmeroRawClient(final URI serverUri) {
        this.serverURI = serverUri;
        this.username = new SimpleStringProperty("");
        this.loggedIn = new SimpleBooleanProperty(false);
        this.isAdminUser = new SimpleBooleanProperty(false);
        this.port = new SimpleIntegerProperty(4064);
        this.simpleClient = new Client();

        // Add the default OMERO server address to the QuPath Preferences
        defaultUsername = PathPrefs.createPersistentPreference("defaultUsername", "");
    }

    private boolean authenticate(final PasswordAuthentication authentication) throws Exception {
        String userName = authentication.getUserName();
        String password = String.valueOf(authentication.getPassword());

        // If the port is unset, use the default one
        if (this.serverURI.getPort() != -1) this.port.set(this.serverURI.getPort());

        //Omero Connect with credentials and simpleLogger
        LoginCredentials credentials = new LoginCredentials();
        credentials.getServer().setHost(this.serverURI.getHost());
        credentials.getServer().setPort(this.port.get());
        credentials.getUser().setUsername(userName);
        credentials.getUser().setPassword(password);

        this.simpleClient.connect(credentials);
        this.isAdminUser.setValue(this.simpleClient.getUser().isAdmin(this.simpleClient));

        return this.simpleClient.isConnected();
    }


    /**
     * switch the current group to another group where the user is also part of
     *
     * @param groupId
     */
    public void switchGroup(long groupId)  {
        // check if the user is member of the group
        boolean canUserAccessGroup = this.simpleClient.getUser().getGroups().stream()
                .map(GroupWrapper::getId)
                .anyMatch(e -> e == groupId);

        // if member, change the group
        if (canUserAccessGroup || this.isAdminUser.get())
            this.simpleClient.switchGroup(groupId);
    }

    /**
     * Return whether the client is logged in to its server (<b>not</b> necessarily with access to all its images).
     *
     * @return isLoggedIn
     */
    public boolean isLoggedIn() { return this.simpleClient.isConnected(); }
    public ExperimenterWrapper getLoggedInUser() { return this.simpleClient.getUser(); }
    public boolean isAdmin() {
        return this.isAdminUser.get();
    }
    public StringProperty usernameProperty() {
        return username;
    }
    public BooleanProperty logProperty() {
        return loggedIn;
    }
    public String getUsername() {
        return username.get();
    }
    public Client getSimpleClient(){return this.simpleClient;}
    public int getPort(){return this.port.get();}
    private Gateway getGateway() {
        return this.simpleClient.getGateway();
    }
    private SecurityContext getContext() { return this.simpleClient.getCtx(); }

    /**
     * Return the server URI ('clean' URI) of this {@code OmeroRawClient}.
     * @return serverUri
     * @see OmeroRawTools#getServerURI(URI)
     */
    public URI getServerURI() {
        return serverURI;
    }

    /**
     * Return an unmodifiable list of all URIs using this {@code OmeroRawClient}.
     * @return list of uris
     * @see #addURI(URI)
     */
    public ObservableList<URI> getURIs() {
        return FXCollections.unmodifiableObservableList(uris);
    }

    /**
     * Add a URI to the list of this client's URIs.
     * <p>
     * Note: there is currently no equivalent 'removeURI()' method.
     * @param uri
     * @see #getURIs()
     */
    public void addURI(URI uri) {
        Platform.runLater(() -> {
            if (!uris.contains(uri))
                uris.add(uri);
            else
                logger.debug("URI already exists in the list. Ignoring operation.");
        });
    }


    /**
     * Log in to the client's server with optional args.
     *
     * @param args
     * @return success
     */
    public boolean logIn(String...args) {
        try {
            // TODO: Parse args to look for password (or password file - and don't store them!)
            String usernameOld = username.get();
            char[] password = null;
            List<String> cleanedArgs = new ArrayList<>();
            int i = 0;
            while (i < args.length-1) {
                String name = args[i++];
                if ("--username".equals(name) || "-u".equals(name))
                    usernameOld = args[i++];
                else if ("--password".equals(name) || "-p".equals(name)) {
                    password = args[i++].toCharArray();
                } else
                    cleanedArgs.add(name);
            }
            if (cleanedArgs.size() < args.length)
                args = cleanedArgs.toArray(String[]::new);

            PasswordAuthentication authentication;

            if (usernameOld != null && password != null) {
                logger.debug("Username & password parsed from args");
                authentication = new PasswordAuthentication(usernameOld, password);
            } else
                authentication = OmeroAuthenticatorFX.getPasswordAuthentication("Please enter your login details for OMERO server", serverURI.toString());
            if (authentication == null)
                return false;

            // get omero port
            this.port.set(OmeroAuthenticatorFX.getPort());
            boolean result = authenticate(authentication);

            Arrays.fill(authentication.getPassword(), (char)0);

            // If we have previous URIs and the username was different
            if (uris.size() > 0 && usernameOld != null && !usernameOld.isEmpty() && !usernameOld.equals(authentication.getUserName())) {
                Utils.infoLog(logger,"OMERO login", String.format("OMERO account switched from \"%s\" to \"%s\" for %s", usernameOld, authentication.getUserName(), serverURI), true);
            } else if (uris.size() == 0 || usernameOld == null || usernameOld.isEmpty())
                Utils.infoLog(logger,"OMERO login", String.format("Login successful: %s(\"%s\")", serverURI, authentication.getUserName()), true);

            // If a browser was currently opened with this client, close it
            if (OmeroRawExtension.getOpenedRawBrowsers().containsKey(this)) {
                var oldBrowser = OmeroRawExtension.getOpenedRawBrowsers().get(this);
                oldBrowser.requestClose();
                OmeroRawExtension.getOpenedRawBrowsers().remove(this);
            }

            // If this method is called from 'project-import' thread (i.e. 'Open URI..'), 'Not on FX Appl. thread' IllegalStateException is thrown
            Platform.runLater(() -> {
                this.loggedIn.set(true);
                this.username.set(authentication.getUserName());
            });

            return true;
        } catch (Exception e) {
           Utils.errorLog(logger, "OMERO login",
                   "Could not connect to OMERO raw server.\nCheck the following:\n- Valid credentials.\n- Access permission.\n- Correct URL.",e,true);
        }
        return false;
    }

    /**
     * Log out this client from the server.
     */
    public void logOut() {
        //TODO add this feature to simple-omero-client
        getGateway().closeConnector(getContext());
        this.simpleClient.disconnect();

        boolean isDone = !this.simpleClient.isConnected();

        logger.info("Disconnection successful: {}", isDone);

        if (isDone) {
            loggedIn.set(false);
            username.set("");
        } else {
            logger.error("Could not logout.");
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverURI, username);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;

        if (!(obj instanceof OmeroRawClient))
            return false;

        return serverURI.equals(((OmeroRawClient)obj).getServerURI()) &&
                getUsername().equals(((OmeroRawClient)obj).getUsername());
    }

    /**
     * check if the current client is connected to the server
     * @return log in status
     */
    public boolean checkIfLoggedIn() {
        if(this.simpleClient == null) // if we invoke the method "createClientAndLogin" in OmeroRawExtension->createRawServerListMenu, the gateway is null
            return false;
        try {
            return getGateway().isAlive(getContext());

        } catch (DSOutOfServiceException e) {
            logger.error( e.getMessage() );
            return false;
        }
    }

    private static class OmeroAuthenticatorFX extends Authenticator {
        private static String port = "4064";

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return getPasswordAuthentication(getRequestingPrompt(), getRequestingHost());
        }

        static int getPort(){
            try {
                return Integer.parseInt(port);
            }catch(Exception e) {
                Utils.warnLog(logger,"OMERO login", " Port " + port + " is not recognized as a correct port. Default port 4064 is used instead", true);
                port = "4064";
                return Integer.parseInt(port);
            }
        }

        static PasswordAuthentication getPasswordAuthentication(String prompt, String host) {
            String lastUsername = defaultUsername.get();

            // create username & password field
            GridPane pane = new GridPane();
            Label labHost = new Label(host);
            Label labUsername = new Label("Username");
            TextField tfUsername = new TextField(lastUsername);
            labUsername.setLabelFor(tfUsername);

            Label labPassword = new Label("Password");
            PasswordField tfPassword = new PasswordField();
            labPassword.setLabelFor(tfPassword);

            // select the right textField
            if(lastUsername.isEmpty())
                Platform.runLater(tfUsername::requestFocus);
            else
                Platform.runLater(tfPassword::requestFocus);

            // create port field
            Label labPort = new Label("Port");
            TextField tfPort = new TextField(port);
            labPort.setLabelFor(tfPort);

            int row = 0;
            if (prompt != null && !prompt.isBlank())
                pane.add(new Label(prompt), 0, row++, 2, 1);
            pane.add(labHost, 0, row++, 2, 1);
            pane.add(labUsername, 0, row);
            pane.add(tfUsername, 1, row++);
            pane.add(labPassword, 0, row);
            pane.add(tfPassword, 1, row++);
            pane.add(labPort, 0, row);
            pane.add(tfPort, 1, row);

            pane.setHgap(5);
            pane.setVgap(5);

            if (!Dialogs.showConfirmDialog("Login", pane))
                return null;

            String userName = tfUsername.getText();
            int passLength = tfPassword.getCharacters().length();
            char[] password = new char[passLength];
            for (int i = 0; i < passLength; i++) {
                password[i] = tfPassword.getCharacters().charAt(i);
            }

            // set omero port & default username
            port = tfPort.getText();
            defaultUsername.set(userName);

            return new PasswordAuthentication(userName, password);
        }
    }
}