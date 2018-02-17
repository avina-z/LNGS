// This source code is released under the GPL v3 license, http://www.gnu.org/licenses/gpl.html.
// This file is part of the LNGS project: http://sourceforge.net/projects/lngooglecalsync.

package lngs.util;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.binary.Base64;

public class ConfigurationManager {

    public ConfigurationManager() {
        // Get the absolute path to this app
        configFullFilename = new java.io.File("").getAbsolutePath() + System.getProperty("file.separator") + configFilename;

        config = new Properties();
    }

    /**
     * Write configuration file.
     * Important: after a writeConfig, the in-memory configuration is not valid anymore until
     * a subsequent readConfig() is performed.
     * @throws IOException
     */
    public void writeConfig() throws IOException {
        // There may be old, unused key/value pairs in the config file.
        // We want to delete these. We'll do it in 3 easy steps.
        
        // (1) Clone the list of keys from the config file
        Hashtable keysToDelete = (Hashtable)config.clone();

        // (2) Use reflection to get the list of keys in this class. These are
        // all the good/current keys we want to keep.
        // Remove these good keys from our cloned list.
        Field[] fields = ConfigurationManager.class.getDeclaredFields();
        for (Field field : fields) {
            try {
                if (field.getName().startsWith("PROP_")) {
                    String key = field.get(this).toString();
                    if (keysToDelete.containsKey(key)) {
                        // Remove the key from our clone list
                        keysToDelete.remove(key);
                    }
                }
            } catch (IllegalArgumentException ex) {
                // Do nothing
            } catch (IllegalAccessException ex) {
                // Do nothing
            }
        }
        
        // (3) Any keys still in the cloned list are bad and need to be
        // deleted from the original config list. When the config list is
        // persisted, the unused keys will no longer be there.
        @SuppressWarnings("unchecked")
        Enumeration<String> enumKey = keysToDelete.keys();
        while(enumKey.hasMoreElements()) {
            config.remove(enumKey.nextElement());
        }

        
        if (getDontSaveSensitiveData()) {
            // Save empty strings for the passwords
            config.setProperty(PROP_PROXY_PASSWORD, "");
            config.setProperty(PROP_LOTUS_NOTES_PASSWORD, "");            
        } else {
            // Starting with v2 of the properties file, we encode passwords but keep them plain in memory
            config.setProperty(PROP_PROXY_PASSWORD, encodePassword(getGoogleProxyPassword()));
            config.setProperty(PROP_LOTUS_NOTES_PASSWORD, encodePassword(getLotusNotesPassword()));
        }

        if (getConfigVersion() < currConfigVersion) {
            config.setProperty(PROP_CONFIG_VERSION, Integer.toString(currConfigVersion));
        }
        else {
            config.setProperty(PROP_CONFIG_VERSION, Integer.toString(getConfigVersion()));
        }

        // Write properties file.
        config.store(new FileOutputStream(configFilename), null);
    }

    public void readConfig() throws IOException {
        config.load(new FileInputStream(configFilename));
        if (getConfigVersion() >= 2) {
            // Starting with v2 of the properties file, we encode passwords but keep them plain in memory
            config.setProperty(PROP_PROXY_PASSWORD, decodePassword(getGoogleProxyPassword()));
            config.setProperty(PROP_LOTUS_NOTES_PASSWORD, decodePassword(getLotusNotesPassword()));
        }
    }

    public void setConfigVersion(int value) {
        config.setProperty(PROP_CONFIG_VERSION, Integer.toString(value));
    }

    public void setApplicationVersion(String value) {
        config.setProperty(PROP_APP_VERSION, value);
    }

    public void setLotusNotesServer(String value) {
        config.setProperty(PROP_LOTUS_NOTES_SERVER, value);
    }

    public void setLotusNotesServerDateFormat(String value) {
        config.setProperty(PROP_LOTUS_NOTES_SERVER_DATE_FORMAT, value);
    }

    public void setLotusNotesServerIsLocal(boolean value) {
        setBooleanProperty(PROP_LOTUS_NOTES_SERVER_IS_LOCAL, value);
    }

    public void setLotusNotesMailFile(String value) {
        config.setProperty(PROP_LOTUS_NOTES_MAIL_FILE, value);
    }

    public void setGoogleUserName(String googleUserName) {
        config.setProperty(PROP_GOOGLE_USERNAME, googleUserName);
    }

    public void setGoogleProxyIP(String googleProxyIP) {
        config.setProperty(PROP_PROXY_IP, googleProxyIP);
    }

    public void setGoogleProxyPort(String googleProxyPort) {
        config.setProperty(PROP_PROXY_PORT, googleProxyPort);
    }

    public void setGoogleProxyUsername(String value) {
        config.setProperty(PROP_PROXY_USERNAME, value);
    }

    public void setGoogleProxyPassword(String value) {
        config.setProperty(PROP_PROXY_PASSWORD, value);
    }

    public void setGoogleEnableProxy(boolean googleEnableProxy) {
        setBooleanProperty(PROP_PROXY_ENABLE, googleEnableProxy);
    }

    public void setGoogleCalendarName(String value) {
        config.setProperty(PROP_GOOGLE_CALENDAR_NAME, value.trim());
    }

    public void setSyncOnStartup(boolean value) {
        setBooleanProperty(PROP_SYNC_ON_STARTUP, value);
    }

    public void setSyncAtMinOffsets(boolean value) {
        setBooleanProperty(PROP_SYNC_AT_MIN_OFFSETS, value);
    }

    public void setSyncMinOffsets(String value) {
        config.setProperty(PROP_SYNC_MIN_OFFSETS, value.trim());
    }

    public void setSyncAllSubjectsToValue(boolean value) {
        setBooleanProperty(PROP_SYNC_ALL_SUBJECTS_TO_VALUE, value);
    }

    public void setSyncAllSubjectsToThisValue(String value) {
        config.setProperty(PROP_SYNC_ALL_SUBJECTS_TO_THIS_VALUE, value.trim());
    }

    public void setDiagnosticMode(boolean value) {
        setBooleanProperty(PROP_DIAGNOSTIC_MODE, value);
    }

    public void setSyncDescription(boolean value) {
        setBooleanProperty(PROP_SYNC_DESCRIPTION, value);
    }

    public void setSyncLocationAndRoom(boolean value) {
        setBooleanProperty(PROP_SYNC_LOCATION_AND_ROOM, value);
    }

    public void setSyncAlarms(boolean value) {
        setBooleanProperty(PROP_SYNC_ALARMS, value);
    }

    public void setSyncDaysInFuture(int value) {
        config.setProperty(PROP_SYNC_DAYS_IN_FUTURE, Integer.toString(value));
    }

    public void setSyncDaysInPast(int value) {
        config.setProperty(PROP_SYNC_DAYS_IN_PAST, Integer.toString(value));
    }

    public void setDontSaveSensitiveData(boolean value) {
        setBooleanProperty(PROP_DONT_SAVE_SENSITIVE_DATA, value);
    }

    public void setLotusNotesPassword(String value) {
        config.setProperty(PROP_LOTUS_NOTES_PASSWORD, value);
    }

    public void setSyncMeetingAttendees(boolean value){
    	setBooleanProperty(PROP_SYNC_MEETING_ATTENDEES, value);
    }

    protected void setBooleanProperty(String propertyName, boolean propertyValue) {
        String propertyValueStr = "false";
        if (propertyValue) {
            propertyValueStr = "true";
        }

        config.setProperty(propertyName, propertyValueStr);
    }


    public int getConfigVersion() {
        return getIntegerProperty(PROP_CONFIG_VERSION);
    }

    public String getApplicationVersion() {
        return getStringProperty(PROP_APP_VERSION);
    }

    public String getLotusNotesServer() {
        return getStringProperty(PROP_LOTUS_NOTES_SERVER);
    }

    public String getLotusNotesServerDateFormat() {
        String value = getStringProperty(PROP_LOTUS_NOTES_SERVER_DATE_FORMAT);

        if (value.isEmpty())
            value = "Detect";

        return value;
    }

    public boolean getLotusNotesServerIsLocal() {
        return getBooleanProperty(PROP_LOTUS_NOTES_SERVER_IS_LOCAL);
    }

    public String getLotusNotesMailFile() {
        return getStringProperty(PROP_LOTUS_NOTES_MAIL_FILE);
    }

    public String getGoogleUserName() {
        return getStringProperty(PROP_GOOGLE_USERNAME);
    }

    public String getGoogleProxyIP() {
        return getStringProperty(PROP_PROXY_IP);
    }

    public String getGoogleProxyPort() {
        return getStringProperty(PROP_PROXY_PORT);
    }

    public String getGoogleProxyUsername() {
        return getStringProperty(PROP_PROXY_USERNAME);
    }

    public String getGoogleProxyPassword() {
        return getStringProperty(PROP_PROXY_PASSWORD);
    }

    public boolean getGoogleEnableProxy() {
        return getBooleanProperty(PROP_PROXY_ENABLE);
    }

    public String getGoogleCalendarName() {
        String value = getStringTrimmedProperty(PROP_GOOGLE_CALENDAR_NAME);

        if (value.isEmpty())
            value = "Lotus Notes";
        
        return value;
    }

    public String getLotusNotesPassword() {
        return getStringProperty(PROP_LOTUS_NOTES_PASSWORD);
    }

    public boolean getSyncOnStartup() {
        return getBooleanProperty(PROP_SYNC_ON_STARTUP);
    }

    public boolean getSyncAtMinOffsets() {
        return getBooleanProperty(PROP_SYNC_AT_MIN_OFFSETS);
    }

    public String getSyncMinOffsets() {
        return getStringTrimmedProperty(PROP_SYNC_MIN_OFFSETS, "0, 15, 30, 45");
    }

    public boolean getSyncAllSubjectsToValue() {
        return getBooleanProperty(PROP_SYNC_ALL_SUBJECTS_TO_VALUE);
    }

    public String getSyncAllSubjectsToThisValue() {
        return getStringTrimmedProperty(PROP_SYNC_ALL_SUBJECTS_TO_THIS_VALUE, "(Private Entry)");
    }

    public boolean getDiagnosticMode() {
        return getBooleanProperty(PROP_DIAGNOSTIC_MODE);
    }

    public boolean getSyncDescription() {
        return getBooleanProperty(PROP_SYNC_DESCRIPTION);
    }

    public boolean getSyncLocationAndRoom() {
        return getBooleanProperty(PROP_SYNC_LOCATION_AND_ROOM, true);
    }

    public boolean getSyncAlarms() {
        return getBooleanProperty(PROP_SYNC_ALARMS);
    }

    public boolean getSyncMeetingAttendees(){
    	return getBooleanProperty(PROP_SYNC_MEETING_ATTENDEES);
    }

    public int getSyncDaysInFuture() {
        return getIntegerProperty(PROP_SYNC_DAYS_IN_FUTURE, 60);
    }

    public int getSyncDaysInPast() {
        return getIntegerProperty(PROP_SYNC_DAYS_IN_PAST, 7);
    }

    public boolean getDontSaveSensitiveData() {
        return getBooleanProperty(PROP_DONT_SAVE_SENSITIVE_DATA);
    }

    // Read a property. If it isn't found, return "".
    protected String getStringProperty(String propertyName) {
        return getStringProperty(propertyName, "");
    }

    // Read a property. If it isn't found, return the default value provided.
    protected String getStringProperty(String propertyName, String defaultValue) {
        String property;

        property = config.getProperty(propertyName);
        if (property == null) {
            property = defaultValue;
        }

        return property;
    }

    // Read a property and trim what's found. If it isn't found, return "".
    protected String getStringTrimmedProperty(String propertyName) {
        return getStringProperty(propertyName).trim();
    }

    // Read a property and trim what's found. If it isn't found, return the default value provided.
    protected String getStringTrimmedProperty(String propertyName, String defaultValue) {
        return getStringProperty(propertyName, defaultValue).trim();
    }

    // Read a property. If it isn't found, return false.
    protected boolean getBooleanProperty(String propertyName) {
        return getBooleanProperty(propertyName, false);
    }

    // Read a property. If it isn't found, return the default value provided.
    protected boolean getBooleanProperty(String propertyName, boolean defaultValue) {
        boolean property = true;

        String propertyStr = config.getProperty(propertyName);
        if (propertyStr == null) {
            property = defaultValue;
        } else if (! propertyStr.equalsIgnoreCase("true")) {
            property = false;
        }

        return property;
    }

    // Read a property value. If the property is not found, return 0.
    protected int getIntegerProperty(String propertyName) {
        return getIntegerProperty(propertyName, 0);
    }

    // Read a property value. If the property is not found, return defaultValue.
    protected int getIntegerProperty(String propertyName, int defaultValue) {
        int property = defaultValue;

        String propertyStr = config.getProperty(propertyName);
        if (propertyStr != null) {
            property = Integer.parseInt(config.getProperty(propertyName));
        }

        return property;
    }

    protected String encodePassword(String password) {
        byte[] encoded = Base64.encodeBase64(password.getBytes());

        return new String(encoded);
    }

    protected String decodePassword(String encodedPassword ) {
        byte[] data = Base64.decodeBase64(encodedPassword);
        
        return new String(data);
    }


    // Version stamp for the config-file format.
    // IMPORTANT: Update this version number whenever there is a format change to the
    // config file, e.g. adding a new setting.
    protected static final int currConfigVersion = 8;
    protected static final String PROP_CONFIG_VERSION = "ConfigVersion";

    protected static final String PROP_APP_VERSION = "ApplicationVersion";

    protected static final String PROP_LOTUS_NOTES_SERVER = "LotusNotesServer";
    protected static final String PROP_LOTUS_NOTES_SERVER_DATE_FORMAT = "LotusNotesServerDateFormat";
    protected static final String PROP_LOTUS_NOTES_SERVER_IS_LOCAL = "LotusNotesServerIsLocal";
    protected static final String PROP_LOTUS_NOTES_MAIL_FILE = "LotusNotesMailFile";
    protected static final String PROP_LOTUS_NOTES_PASSWORD = "LotusNotesPassword";

    protected static final String PROP_DIAGNOSTIC_MODE = "DiagnosticMode";
    protected static final String PROP_SYNC_ON_STARTUP = "SyncOnStartup";
    protected static final String PROP_SYNC_AT_MIN_OFFSETS = "SyncAtMinOffsets";
    protected static final String PROP_SYNC_MIN_OFFSETS = "SyncMinOffsets";
    protected static final String PROP_SYNC_ALL_SUBJECTS_TO_VALUE = "SyncAllSubjectsToValue";
    protected static final String PROP_SYNC_ALL_SUBJECTS_TO_THIS_VALUE = "SyncAllSubjectsToThisValue";
    protected static final String PROP_SYNC_DESCRIPTION = "SyncDescription";
    protected static final String PROP_SYNC_LOCATION_AND_ROOM = "SyncLocationAndRoom";
    protected static final String PROP_SYNC_ALARMS = "SyncAlarms";
    protected static final String PROP_SYNC_MEETING_ATTENDEES = "SyncMeetingAttendees";
    protected static final String PROP_SYNC_DAYS_IN_FUTURE = "SyncDaysInFuture";
    protected static final String PROP_SYNC_DAYS_IN_PAST = "SyncDaysInPast";
    protected static final String PROP_DONT_SAVE_SENSITIVE_DATA = "DontSaveSensitiveData";

    protected static final String PROP_PROXY_ENABLE = "GoogleEnableProxy";
    protected static final String PROP_PROXY_USERNAME = "GoogleProxyUsername";
    protected static final String PROP_PROXY_PASSWORD = "GoogleProxyPassword";
    protected static final String PROP_PROXY_IP = "GoogleProxyIP";
    protected static final String PROP_PROXY_PORT = "GoogleProxyPort";
    protected static final String PROP_GOOGLE_USERNAME = "GoogleUsername";
    protected static final String PROP_GOOGLE_CALENDAR_NAME = "GoogleCalendarName";

    protected Properties config;
    protected final String configFilename = "lngsync.config";
    // Filename with full path
    protected String configFullFilename;
}
