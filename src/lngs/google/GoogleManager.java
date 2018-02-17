// This source code is released under the GPL v3 license, http://www.gnu.org/licenses/gpl.html.
// This file is part of the LNGS project: http://sourceforge.net/projects/lngooglecalsync.
package lngs.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.GoogleUtils;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;

import lngs.lotus.LotusNotesCalendarEntry;

import lngs.util.LngsException;
import lngs.util.StatusMessageCallback;

import java.io.*;

import java.net.*;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


public class GoogleManager {
    protected static final String CLIENT_CREDENTIAL_FILENAME = "client_credential";

    // Global instance of the JSON factory.
    protected static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static com.google.api.services.calendar.Calendar client;
    private static com.google.api.services.calendar.model.Calendar destCalendar = null;
    protected static String destinationCalendarName = "";
    protected final String applicationName = "LNGS";
    protected final String credentialStorePath = System.getProperty("user.home") +
        System.getProperty("file.separator") + ".store" +
        System.getProperty("file.separator") + "LNGS";
    FileDataStoreFactory dataStoreFactory = null;
    GoogleClientSecrets clientSecrets = null;
    protected StatusMessageCallback statusMessageCallback = null;
    protected URL mainCalendarFeedUrl = null;
    protected URL privateCalendarFeedUrl = null;
    protected URL destinationCalendarFeedUrl = null;
    protected String googleUsername;
    protected String DEST_CALENDAR_COLOR = "#FFAD40";

    // Debug file info
    protected BufferedWriter googleInRangeEntriesWriter = null;
    protected File googleInRangeEntriesFile = null;
    protected final String googleInRangeEntriesFilename = "GoogleInRangeEntries.txt";

    // Filename with full path
    protected String googleInRangeEntriesFullFilename = "";
    protected String appPath = "";
    protected boolean diagnosticMode = false;
    protected boolean syncDescription = false;
    protected boolean syncWhere = false;
    protected boolean syncAllSubjectsToValue = false;
    protected String syncAllSubjectsToThisValue = "";
    protected boolean syncAlarms = false;
    protected boolean syncMeetingAttendees = false;

    // Our min and max dates for entries we will process.
    // If the calendar entry is outside this range, it is ignored.
    protected Date minStartDate = null;
    protected Date maxEndDate = null;
    protected String clientSecretRegExFilename = "";
    protected final int maxRetryCount = 5;
    protected final int retryDelayMsecs = 600;

    // Google has a maximum limit of around 1600 chars for subject/title lines.
    // I don't know the Lotus limit, but 1000 should be plenty.
    protected final int maxSubjectChars = 1000;

    // The maximum number of chars allowed in a calendar description. Google has some
    // limit around 8100 chars. Lotus has a limit greater than that, so choose 8000.
    protected final int maxDescriptionChars = 8000;

    // Create a SSL Certificate Trust Manager that trusts all certificates.
    // This is *not* secure, but may be needed by some people to get around
    // tight security on their network.
    final TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(
                        final X509Certificate[] chain, final String authType) {
                    }

                    @Override
                    public void checkServerTrusted(
                        final X509Certificate[] chain, final String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                }
        };

    public GoogleManager() {
        // Get the absolute path to this app
        appPath = new java.io.File("").getAbsolutePath() +
            System.getProperty("file.separator");
        googleInRangeEntriesFullFilename = appPath +
            googleInRangeEntriesFilename;
    }

    /**
     * Login to Google and connect to the calendar.
     */
    public void connect() throws LngsException, InterruptedException {
        final String ERROR_HTTP_TRANSPORT = "Unable to setup HTTP transport for Google login.";

        statusMessageCallback.statusAppendStart("Logging into Google");

        String clientIdFullFilename = getClientIdFilename();

        if (clientIdFullFilename.isEmpty()) {
            throw new LngsException("Client ID file could not be found.");
        } else {
            statusMessageCallback.statusAppendLineDiag("Found Client ID file: " +
                clientIdFullFilename);
        }

        HttpTransport httpTransport = null;

        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            SSLSocketFactory sslSocketFactory = null;

            // Use the default trust manager
            sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();

            // This is test code trust all SSL certifificates. This is *not*
            // a secure or recommended thing to do, but might be needed on
            // some networks.
            //            boolean dontVerifyCertificates = false;            
            //            if (dontVerifyCertificates) {
            //                statusMessageCallback.statusAppendLineDiag("WARNING: SSL Certificates won't be verified.");
            //                
            //                // Install the all-trusting trust manager
            //                SSLContext sslContext = SSLContext.getInstance("SSL");
            //                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            //
            //                // Create an ssl socket factory with our all-trusting manager
            //                sslSocketFactory = sslContext.getSocketFactory();
            //            }
            NetHttpTransport.Builder builder = new NetHttpTransport.Builder();
            builder.trustCertificates(GoogleUtils.getCertificateTrustStore());

            builder.setSslSocketFactory(sslSocketFactory);
            httpTransport = builder.build();
        } catch (GeneralSecurityException ex) {
            throw new LngsException(ERROR_HTTP_TRANSPORT, ex);
        } catch (IOException ex) {
            throw new LngsException(ERROR_HTTP_TRANSPORT, ex);
        }

        try {
            boolean doRetry = true;
            int retryCount = 0;

            do {
                try {
                    // Initialize the data store factory
                    if (dataStoreFactory == null) {
                        dataStoreFactory = new FileDataStoreFactory(new java.io.File(
                                    appPath));
                    }

                    // Load client secrets
                    if (clientSecrets == null) {
                        clientSecrets = GoogleClientSecrets.load(JSON_FACTORY,
                                new FileReader(clientIdFullFilename));
                    }

                    // Set up authorization code flow
                    GoogleAuthorizationCodeFlow.Builder authBuilder = new GoogleAuthorizationCodeFlow.Builder(httpTransport,
                            JSON_FACTORY, clientSecrets,
                            Collections.singleton(CalendarScopes.CALENDAR));
                    DataStore<StoredCredential> ds = dataStoreFactory.getDataStore(CLIENT_CREDENTIAL_FILENAME);
                    GoogleAuthorizationCodeFlow flow = authBuilder.setCredentialDataStore(ds)
                                                                  .build();

                    // Authorize with OAuth2
                    Credential credential = new AuthorizationCodeInstalledApp(flow,
                            new LocalServerReceiver()).authorize(googleUsername);

                    // Set up global Calendar instance
                    client = new com.google.api.services.calendar.Calendar.Builder(httpTransport,
                            JSON_FACTORY, credential).setApplicationName(applicationName)
                                                                                                                           .build();

                    if (client != null) {
                        doRetry = false;
                    }
                } catch (Exception ex) {
                    if (++retryCount > maxRetryCount) {
                        throw new LngsException("Unable to login to Google.", ex);
                    }

                    statusMessageCallback.statusAppendLineDiag(
                        "Logging in Retry #" + retryCount + ". Encountered " +
                        ex.toString());
                    Thread.sleep(retryDelayMsecs);

                    if (ex.toString().contains("unauthorized_client")) {
                        statusMessageCallback.statusAppendLineDiag(
                            "Trying to fix problem by deleting/recreating credential file.");

                        // This error typically means there is an existing credential store that
                        // doesn't contain the correct ID.
                        // So delete the credential store directory so it can be re-created
                        String credentialFilename = credentialStorePath +
                            System.getProperty("file.separator") +
                            "StoredCredential";
                        File file = new File(credentialFilename);

                        if (!file.delete()) {
                            statusMessageCallback.statusAppendLineDiag(
                                "Failed to delete: " + credentialFilename);
                        } else {
                            // Manually deleting the credential file always seems to resolve the
                            // unauthorized client error, but the programatic deletion doesn't always work.
                            // Add this delay to see if it helps.
                            Thread.sleep(1000);
                        }
                    }
                }
            } while (doRetry);

            try {
                createCalendar();
            } catch (Exception ex) {
                throw new LngsException("Unable to create Google calendar.", ex);
            }

            if (diagnosticMode) {
                // Get this machine's current time zone
                TimeZone localTimeZone = TimeZone.getDefault();
                String timeZoneName = localTimeZone.getID();
                statusMessageCallback.statusAppendLineDiag(
                    "Local Machine Time Zone: " + timeZoneName);

                statusMessageCallback.statusAppendLineDiag(
                    "Dest Calendar Time Zone: " + getDestinationTimeZone());
            }
        } finally {
            statusMessageCallback.statusAppendFinished();
        }
    }

    public String getClientIdFilename() {
        Pattern pattern = Pattern.compile("^client_secret.*\\.json$");

        File dir = new File(appPath);

        File[] files = dir.listFiles();

        for (int i = 0; i < files.length; i++) {
            String fileName = files[i].getName();

            if (pattern.matcher(fileName).find()) {
                if (files[i].isFile()) {
                    return files[i].getPath();
                }
            }
        }

        return "";
    }

    /**
     * Creates a Google calendar for the desired name (if it doesn't already exist).
     * @throws IOException
     * @throws LngsException
     */
    public void createCalendar() throws IOException, LngsException {
        // If true, we already have a reference to the calendar
        if (destCalendar != null) {
            return;
        }

        CalendarList feed = client.calendarList().list().execute();

        if (feed == null) {
            throw new LngsException("Google calendar list is empty.");
        }

        if (feed.getItems() != null) {
            for (CalendarListEntry entry : feed.getItems()) {
                if (entry.getSummary().equals(destinationCalendarName)) {
                    // Get the Calendar object
                    destCalendar = client.calendars().get(entry.getId())
                                         .execute();
                    statusMessageCallback.statusAppendLineDiag(
                        "Found Google calendar: " + entry.getSummary());

                    return;
                }
            }
        }

        // Get this machine's current time zone when creating the new Google calendar
        TimeZone localTimeZone = TimeZone.getDefault();
        String timeZoneName = localTimeZone.getID();

        // The calendar wasn't found, and will be created
        statusMessageCallback.statusAppendLineDiag("Creating calendar named '" +
            destinationCalendarName + "'");

        com.google.api.services.calendar.model.Calendar newCal = new com.google.api.services.calendar.model.Calendar();

        newCal.setSummary(destinationCalendarName);
        newCal.setTimeZone(timeZoneName);

        destCalendar = client.calendars().insert(newCal).execute();

        // Try this update code to set background color        
        //com.google.api.services.calendar.model.Calendar entry = new com.google.api.services.calendar.model.Calendar();
        //entry.setSummary("Updated Calendar for Testing");
        //com.google.api.services.calendar.model.Calendar result = client.calendars().patch(calendar.getId(), entry).execute();
        CalendarListEntry newCalEntry = client.calendarList()
                                              .get(destCalendar.getId())
                                              .execute();
        newCalEntry.setHidden(false);
        newCalEntry.setSelected(true);
        newCalEntry.setBackgroundColor(DEST_CALENDAR_COLOR);
        newCalEntry.setSelected(true);
        newCalEntry.setBackgroundColor(DEST_CALENDAR_COLOR);
        client.calendarList().patch(newCalEntry.getId(), newCalEntry).execute();
    }

    /**
     * Delete the Google calendar entries in the provided list.
     * @return The number of entries successfully deleted.
     */
    public int deleteCalendarEntries(ArrayList<Event> googleCalEntries)
        throws IOException {
        if (googleCalEntries.size() == 0) {
            return 0;
        }

        int cntDeleted = googleCalEntries.size();

        for (int i = 0; i < googleCalEntries.size(); i++) {
            Event event = googleCalEntries.get(i);
            String startStr = "" +
                ((event.getStart().getDateTime() != null)
                ? event.getStart().getDateTime() : event.getStart().getDate());

            statusMessageCallback.statusAppendLineDiag("Delete #" + (i + 1) +
                ". Subject: " + event.getSummary() + "  Start: " + startStr);
            client.events()
                  .delete(destCalendar.getId(), googleCalEntries.get(i).getId())
                  .execute();
        }

        return cntDeleted;
    }

    /**
     * Get all the Google calendar entries for a specific date range.
     * @return The found entries.
     */
    public ArrayList<Event> getCalendarEntries()
        throws InterruptedException, LngsException {
        try {
            statusMessageCallback.statusAppendStart(
                "Getting Google calendar entries");

            // Get all events within our date range
            com.google.api.client.util.DateTime minDate = new com.google.api.client.util.DateTime(minStartDate);
            com.google.api.client.util.DateTime maxDate = new com.google.api.client.util.DateTime(maxEndDate);

            ArrayList<Event> allCalEntries = new ArrayList<Event>();
            Events events = new Events();

            String pageToken = null;
            int retryCount = 0;
            int queryCount = 0;
            int entriesReturned = 0;

            // Run our query as many times as necessary to get all the
            // Google calendar entries we want
            do {
                try {
                    // Execute the query and get the response
                    // Set the maximum number of results to return for the query.
                    // Note: The server may choose to provide fewer results, but will never provide
                    // more than the requested maximum.
                    events = client.events().list(destCalendar.getId())
                                   .setTimeZone(destCalendar.getTimeZone())
                                   .setTimeMin(minDate).setTimeMax(maxDate)
                                   .setMaxResults(1000).setPageToken(pageToken)
                                   .execute();
                } catch (Exception ex) {
                    // If there is a network problem while connecting to Google, retry a few times
                    if (++retryCount > maxRetryCount) {
                        throw new LngsException("Unable to get Google calendar entries after multiple retries.",
                            ex);
                    }

                    Thread.sleep(retryDelayMsecs);

                    statusMessageCallback.statusAppendLineDiag("Query Retry #" +
                        retryCount + ". Encountered " + ex.toString());

                    if (retryCount == 1) {
                        // Write out the stack trace
                        StringWriter sw = new StringWriter();
                        ex.printStackTrace(new PrintWriter(sw));
                        statusMessageCallback.statusAppendLineDiag(sw.toString());
                    }

                    continue;
                }

                queryCount++;

                if (events.getItems() != null) {
                    statusMessageCallback.statusAppendLineDiag(events.getItems()
                                                                     .size() +
                        " entries returned by query #" + queryCount);

                    // Add the returned entries to our local list
                    allCalEntries.addAll(events.getItems());
                }

                pageToken = events.getNextPageToken();
            } while (pageToken != null);

            // Remove all entries marked canceled. Canceled entries aren't visible
            // in Google calendar, and trying to delete them programatically will
            // cause an exception.
            for (int i = 0; i < allCalEntries.size(); i++) {
                Event evt = allCalEntries.get(i);

                if (evt.getStatus().equals("cancelled")) {
                    allCalEntries.remove(evt);
                    i--;
                }
            }

            if (diagnosticMode) {
                writeInRangeEntriesToFile(allCalEntries);
            }

            return allCalEntries;
        } catch (IOException ex) {
            throw new LngsException("Unable to get Google calendar entries.", ex);
        } finally {
            statusMessageCallback.statusAppendFinished();
        }
    }

    /**
     * Write key parts of the Google calendar entries to a text file.
     * @param calendarEntries - The calendar entries to process.
     */
    public void writeInRangeEntriesToFile(ArrayList<Event> calendarEntries)
        throws IOException {
        try {
            // Open the output file if it is not open
            if (googleInRangeEntriesWriter == null) {
                googleInRangeEntriesFile = new File(googleInRangeEntriesFullFilename);
                googleInRangeEntriesWriter = new BufferedWriter(new FileWriter(
                            googleInRangeEntriesFile));
            }

            if (calendarEntries == null) {
                googleInRangeEntriesWriter.write(
                    "The calendar entries list is empty.\n");
            } else {
                googleInRangeEntriesWriter.write("Total entries: " +
                    calendarEntries.size() + "\n\n");
            }

            for (Event calEntry : calendarEntries) {
                googleInRangeEntriesWriter.write("=== Calendar Entry ===\n");

                googleInRangeEntriesWriter.write("  Title: " +
                    calEntry.getSummary() + "\n");

                googleInRangeEntriesWriter.write("  Where: " +
                    calEntry.getLocation() + "\n");

                googleInRangeEntriesWriter.write("  IcalUID: " +
                    calEntry.getICalUID() + "\n");
                googleInRangeEntriesWriter.write("  Start DateTime:   " +
                    calEntry.getStart().getDateTime() + "\n");
                googleInRangeEntriesWriter.write("  End DateTime:     " +
                    calEntry.getEnd().getDateTime() + "\n");
                googleInRangeEntriesWriter.write("  Start Date:       " +
                    calEntry.getStart().getDate() + "\n");
                googleInRangeEntriesWriter.write("  End Date:         " +
                    calEntry.getEnd().getDate() + "\n");
                googleInRangeEntriesWriter.write("  Updated Date: " +
                    calEntry.getUpdated() + "\n");
                googleInRangeEntriesWriter.write("  Alarm: " +
                    ((calEntry.getReminders() != null)
                    ? calEntry.getReminders().toPrettyString() : "none") +
                    "\n");
                googleInRangeEntriesWriter.write("  Description: |" +
                    calEntry.getDescription() + "|\n");

                googleInRangeEntriesWriter.write("\n\n");
            }
        } catch (IOException ex) {
            throw ex;
        } finally {
            if (googleInRangeEntriesWriter != null) {
                googleInRangeEntriesWriter.close();
                googleInRangeEntriesWriter = null;
            }
        }
    }

    /**
     * Compare the Lotus and Google entries based on the Lotus modified timestamp
     * and other items.
     * On exit, lotusCalEntries will only contain the entries we want created and
     * googleCalEntries will only contain the entries we want deleted.
     */
    public void compareCalendarEntries(
        ArrayList<LotusNotesCalendarEntry> lotusCalEntries,
        ArrayList<Event> googleCalEntries) {
        // Loop through all Google entries and remove entries that were created in GCal (not by LNGS)
        for (int j = 0; j < googleCalEntries.size(); j++) {
            if (!LotusNotesCalendarEntry.isLNGSUID(googleCalEntries.get(j)
                                                                       .getICalUID())) {
                // The Google entry was NOT created by LNGS, so we want to remove it from
                // our processing list (i.e. we will leave it alone).
                googleCalEntries.remove(j--);

                //statusMessageCallback.statusAppendLineDiag("Compare: Google entry NOT created by LNGS: " + googleCalEntries.get(j).getSummary());
            }
        }

        // Loop through all Lotus entries
        for (int i = 0; i < lotusCalEntries.size(); i++) {
            LotusNotesCalendarEntry lotusEntry = lotusCalEntries.get(i);

            // Loop through all Google entries for each Lotus entry.  This isn't
            // very efficient, but we have small lists (probably less than 300).
            for (int j = 0; j < googleCalEntries.size(); j++) {
                if (!hasEntryChanged(lotusEntry, googleCalEntries.get(j))) {
                    // The Lotus and Google entries are identical, so remove them from out lists.
                    // They don't need created or deleted.
                    lotusCalEntries.remove(i--);
                    googleCalEntries.remove(j--);

                    break;
                } else {
                    //statusMessageCallback.statusAppendLineDiag("Compare: Lotus entry needs created in GCal: " + lotusEntry.getSubject());
                }
            }
        }
    }

    /**
     * Compare a Lotus and Google entry
     * Return true if the Lotus entry has changed since the last sync.
     * Return false if the two entries are equivalent.
     */
    public boolean hasEntryChanged(LotusNotesCalendarEntry lotusEntry,
        Event googleEntry) {
        final int googleUIDIdx = 33;

        String syncUID = lotusEntry.getSyncUID();

        // The Google IcalUID has the format: GoogleUID:SyncUID. Strip off the 
        // "GoogleUID:" part and do a compare of the SyncUID.
        // The SyncUID contains several pieces of info, including the Lotus modified
        // timestamp. Most changes to a Lotus entry will update this timestamp. Therefore,
        // this compare will catch the vast majority of the changes between Lotus/Google.
        if (googleEntry.getICalUID().substring(googleUIDIdx).equals(syncUID)) {
            // The Google and Lotus entries match on our first test, but we have to compare
            // other values. Why? Say a sync is performed with the "sync alarms"
            // option enabled, but then "sync alarms" is turned off. When the
            // second sync happens, we want to delete all the Google entries created
            // the first time (with alarms) and re-create them without alarms.
            //String startStr = "" + (googleEntry.getStart().getDateTime() != null ? googleEntry.getStart().getDateTime() : googleEntry.getStart().getDate());
            //statusMessageCallback.statusAppendLineDiag("Compare: UIDs match. Subj: " + googleEntry.getSummary() +
            //    "  Start: " + startStr);

            // Compare the title/subject
            String lotusSubject = createSubjectText(lotusEntry);

            if ((googleEntry.getSummary() == null) ||
                    !googleEntry.getSummary().equals(lotusSubject)) {
                //statusMessageCallback.statusAppendLineDiag("Compare: Subjects differ");
                return true;
            }

            // If true, we want location/where info in our Google entries and the Lotus
            // entry has location info to add.
            if (syncWhere && (lotusEntry.getGoogleWhereString() != null)) {
                // If true, the Google entry doesn't contain location info, so the entries don't match.
                if ((googleEntry.getLocation() == null) ||
                        googleEntry.getLocation().isEmpty()) {
                    //statusMessageCallback.statusAppendLineDiag("Compare: No Google location info");;
                    return true;
                }
            } else {
                // If true, the Google entry has location info (which we don't want), so the entries don't match.
                if ((googleEntry.getLocation() != null) &&
                        !googleEntry.getLocation().isEmpty()) {
                    //statusMessageCallback.statusAppendLineDiag("Compare: No Lotus location info. GCal Location: " + googleEntry.getLocation());
                    return true;
                }
            }

            boolean hasLngsReminder = false;

            // If true, then a non-default override reminder has previously been set by LNGS
            if ((googleEntry.getReminders() != null) &&
                    !googleEntry.getReminders().getUseDefault() &&
                    (googleEntry.getReminders().getOverrides() != null)) {
                hasLngsReminder = true;
            }

            if (syncAlarms) {
                if (lotusEntry.getAlarm()) {
                    // We are syncing alarms, so make sure the Google entry has an alarm.
                    // LNGS always sets alarms as UseDefault=false, so UseDefault=true means
                    // we want to update the entry.
                    // Note: If there is an alarm set, we'll assume the alarm offset is correct.
                    if (!hasLngsReminder) {
                        //statusMessageCallback.statusAppendLineDiag("Compare: No GCal reminder, but has Lotus reminder");
                        return true;
                    }
                } else {
                    // We aren't syncing alarms, so make sure the Google entry doesn't
                    // have an alarm specified
                    if (hasLngsReminder) {
                        //statusMessageCallback.statusAppendLineDiag("Compare: No Lotus reminder, but has GCal reminder");
                        return true;
                    }
                }
            } else {
                if (hasLngsReminder) {
                    //statusMessageCallback.statusAppendLineDiag("Compare: Not syncing reminder, but has GCal reminder");
                    return true;
                }
            }

            if (googleEntry.getDescription() == null) {
                googleEntry.setDescription("");
            }

            // Compare the Description field of Google entry to what we would build it as
            if (!googleEntry.getDescription()
                                .equals(createDescriptionText(lotusEntry))) {
                //statusMessageCallback.statusAppendLineDiag("Compare: Descriptions differ");
                return true;
            }

            // The Lotus and Google entries are identical
            return false;
        }

        return true;
    }

    // This method is for testing purposes.
    //    public void createSampleCalEntry() {
    //        LotusNotesCalendarEntry cal = new LotusNotesCalendarEntry();
    //        cal.setSubject("RepeatTest");
    //        cal.setEntryType(LotusNotesCalendarEntry.EntryType.APPOINTMENT);
    //        cal.setAppointmentType("3");
    //        cal.setLocation("nolocation");
    //        cal.setRoom("noroom");
    //
    //        Date dstartDate, dendDate;
    //        Calendar now = Calendar.getInstance();
    //        now.set(Calendar.YEAR, 2010);
    //        now.set(Calendar.MONTH, 7);  // Month is relative zero
    //        now.set(Calendar.DAY_OF_MONTH, 2);
    //        now.set(Calendar.HOUR_OF_DAY, 10);
    //        now.set(Calendar.MINUTE, 0);
    //        now.set(Calendar.SECOND, 0);
    //        dstartDate = now.getTime();
    //        cal.setStartDateTime(dstartDate);
    //
    //        now.set(Calendar.HOUR_OF_DAY, 11);
    //        dendDate = now.getTime();
    //        cal.setEndDateTime(dendDate);
    //
    //        DateTime startTime, endTime;
    //
    //        CalendarEventEntry event = new CalendarEventEntry();
    //        event.setTitle(new PlainTextConstruct(cal.getSubject()));
    //
    //        String whereStr = cal.getGoogleWhereString();
    //        if (whereStr != null) {
    //            Where location = new Where();
    //            location.setValueString(whereStr);
    //            event.addLocation(location);
    //        }
    //
    //        try {
    //            When eventTime = new When();
    //            eventTime.setStartTime(DateTime.parseDateTime(cal.getStartDateTimeGoogle()));
    //            eventTime.setEndTime(DateTime.parseDateTime(cal.getEndDateTimeGoogle()));
    //            event.addTime(eventTime);
    //
    //            Date dstartDate2, dendDate2;
    //            now.set(Calendar.DAY_OF_MONTH, 3);
    //            now.set(Calendar.HOUR_OF_DAY, 10);
    //            dstartDate2 = now.getTime();
    //            cal.setStartDateTime(dstartDate2);
    //            now.set(Calendar.HOUR_OF_DAY, 11);
    //            dendDate2 = now.getTime();
    //            cal.setEndDateTime(dendDate2);
    //
    //            eventTime = new When();
    //            eventTime.setStartTime(DateTime.parseDateTime(cal.getStartDateTimeGoogle()));
    //            eventTime.setEndTime(DateTime.parseDateTime(cal.getEndDateTimeGoogle()));
    //            event.addTime(eventTime);
    //            int j = event.getTimes().size();
    //            j++;
    //
    //            service.insert(getDestinationCalendarUrl(), event);
    //        } catch (Exception e) {
    //        }
    //    }

    /**
     * Create Lotus Notes calendar entries in the Google calendar.
     * @param lotusCalEntries - The list of Lotus Notes calendar entries.
     * @return The number of Google calendar entries successfully created.
     * @throws LngsException
     * @throws IOException
     */
    public int createCalendarEntries (
        ArrayList<LotusNotesCalendarEntry> lotusCalEntries)
        throws LngsException, IOException {
        int retryCount = 0;
        int createdCount = 0;

        for (int i = 0; i < lotusCalEntries.size(); i++) {
            LotusNotesCalendarEntry lotusEntry = lotusCalEntries.get(i);
            Event event = new Event();
            // Set the subject/title
            event.setSummary(createSubjectText(lotusEntry));

            // The Google IcalUID must be unique or we'll get a
            // VersionConflictException during the insert. So start the IcalUID string
            // with a newly generate UUID (with the '-' chars removed).  Then add the values
            // we really want to remember (referred to as the SyncUID).
            event.setICalUID(UUID.randomUUID().toString().replaceAll("-", "") +
                ":" + lotusEntry.getSyncUID());

            StringBuffer sb = new StringBuffer();

            // Set the body/description
            event.setDescription(createDescriptionText(lotusEntry));

            if (syncWhere) {
                String whereStr = lotusEntry.getGoogleWhereString();

                if (whereStr != null) {
                    // Remove all control/non-printing characters from the Where string. If present, such
                    // characters will cause the GCal create to fail.
                    event.setLocation(whereStr.replaceAll("\\p{Cntrl}", ""));
                }
            }

            boolean allDayEvent = false;
            Date startTime;
            Date endTime;

            if ((lotusEntry.getEntryType() == LotusNotesCalendarEntry.EntryType.TASK) ||
                    (lotusEntry.getAppointmentType() == LotusNotesCalendarEntry.AppointmentType.ALL_DAY_EVENT) ||
                    (lotusEntry.getAppointmentType() == LotusNotesCalendarEntry.AppointmentType.ANNIVERSARY)) {
                allDayEvent = true;

                // Create an all-day event by setting start/end dates with no time portion
                startTime = lotusEntry.getStartDate(0);

                if (lotusEntry.getEndDateTime() == null) {
                    // Use start date since the end date is null
                    endTime = lotusEntry.getStartDate(1);
                } else {
                    endTime = lotusEntry.getEndDate(1);
                }
            } else if ((lotusEntry.getAppointmentType() == LotusNotesCalendarEntry.AppointmentType.APPOINTMENT) ||
                    (lotusEntry.getAppointmentType() == LotusNotesCalendarEntry.AppointmentType.MEETING)) {
                // Create a standard event
                startTime = lotusEntry.getStartDateTime();

                if (lotusEntry.getEndDateTime() == null) {
                    // Use start date since the end date is null
                    endTime = lotusEntry.getStartDateTime();
                } else {
                    endTime = lotusEntry.getEndDateTime();
                }
            } else if (lotusEntry.getAppointmentType() == LotusNotesCalendarEntry.AppointmentType.REMINDER) {
                // Create a standard event with the start and end times the same
                startTime = lotusEntry.getStartDateTime();
                endTime = lotusEntry.getStartDateTime();
            } else {
                throw new LngsException(
                    "Couldn't determine Lotus Notes event type.\nEvent subject: " +
                    lotusEntry.getSubject() + "\nEntry Type: " +
                    lotusEntry.getEntryType() + "\nAppointment Type: " +
                    lotusEntry.getAppointmentType());
            }

            EventDateTime startEdt = new EventDateTime();
            EventDateTime endEdt = new EventDateTime();

            if (allDayEvent) {
                // Set the date only, no time portion
                DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                startEdt.setDate(new com.google.api.client.util.DateTime(
                        dateFormat.format(startTime)));
                endEdt.setDate(new com.google.api.client.util.DateTime(
                        dateFormat.format(endTime)));
            } else {
                startEdt.setDateTime(new com.google.api.client.util.DateTime(
                        startTime));
                endEdt.setDateTime(new com.google.api.client.util.DateTime(
                        endTime));
            }

            event.setStart(startEdt);
            event.setEnd(endEdt);

            Event.Reminders reminders = new Event.Reminders();
            // Each Google Calendar can have 0 to 5 default reminders/notifications which are used when
            // a new calendar entry is created. If syncAlarms is false, then the GCal default reminders
            // will be used. When true, the Lotus Notes alarms are used.
            reminders.setUseDefault(true);

            if (syncAlarms) {
                reminders.setUseDefault(false);

                if (lotusEntry.getAlarm()) {
                    com.google.api.services.calendar.model.EventReminder reminder =
                        new com.google.api.services.calendar.model.EventReminder();

                    reminder.setMinutes(lotusEntry.getAlarmOffsetMinsGoogle());
                    reminder.setMethod("popup");
                    
                    ArrayList<com.google.api.services.calendar.model.EventReminder> over =
                        new ArrayList<com.google.api.services.calendar.model.EventReminder>();
                    over.add(reminder);
                    reminders.setOverrides(over);
                }
            }

            // Always set the GCal reminder. It will either be empty or have
            // the Lotus value.
            event.setReminders(reminders);

            // If the Lotus Notes entry has the Mark Private checkbox checked, then
            // mark the entry private in Google
            if (lotusEntry.getPrivate()) {
                event.setVisibility("private");
            }

            retryCount = 0;

            do {
                String startStr = "" +
                    ((event.getStart().getDateTime() != null)
                    ? event.getStart().getDateTime() : event.getStart().getDate());

                createdCount++;
                statusMessageCallback.statusAppendLineDiag("Create #" +
                    createdCount + ". Subject: " + event.getSummary() +
                    "  Start: " + startStr + "  Type: " +
                    lotusEntry.getAppointmentType());
                client.events().insert(destCalendar.getId(), event).execute();

                break;
            } while (true);
        }

        return createdCount;
    }

    /**
     * Build the GCal subject text from the Lotus Notes calendar entry.
     * @param lotusEntry - The source Lotus Notes calendar entry.
     * @return The GCal subject text.
     */
    protected String createSubjectText(LotusNotesCalendarEntry lotusEntry) {
        String subjectText = "";

        if (syncAllSubjectsToValue) {
            subjectText = syncAllSubjectsToThisValue;
        } else {
            // Remove carriage returns
            subjectText = lotusEntry.getSubject().trim().replace("\r", "");
        }

        if (subjectText.length() > maxSubjectChars) {
            // Truncate to a max size
            subjectText = subjectText.substring(0, maxSubjectChars);
        }

        return subjectText;
    }

    /**
     * Build the GCal description text from the Lotus Notes calendar entry. The
     * output includes the LN description and optional info like the invitees.
     * @param lotusEntry - The source Lotus Notes calendar entry.
     * @return The GCal description text.
     */
    protected String createDescriptionText(LotusNotesCalendarEntry lotusEntry) {
        StringBuffer sb = new StringBuffer();

        if (syncMeetingAttendees) {
            if (lotusEntry.getChairpersonPlain() != null) {
                //chair comes out in format: CN=Jonathan Marshall/OU=UK/O=IBM, leaving like that at the moment
                sb.append("Chairperson: ");
                sb.append(lotusEntry.getChairpersonPlain());
            }

            if (lotusEntry.getRequiredAttendeesPlain() != null) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }

                sb.append("Required: ");
                sb.append(lotusEntry.getRequiredAttendeesPlain());
            }

            if (lotusEntry.getOptionalAttendees() != null) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }

                sb.append("Optional: ");
                sb.append(lotusEntry.getOptionalAttendees());
            }
        }

        if (syncDescription && (lotusEntry.getBody() != null)) {
            if (sb.length() > 0) {
                // Put blank lines between attendees and the description
                sb.append("\n\n\n");
            }

            // Lotus ends each description line with \r\n.  Remove all
            // carriage returns (\r) because they aren't needed and they prevent the
            // Lotus description from matching the description in Google.
            String s = lotusEntry.getBody().replace("\r", "");
            sb.append(s.trim());
        }

        // Return a string truncated to a max size
        return sb.toString()
                 .substring(0,
            (sb.length() < maxDescriptionChars) ? sb.length()
                                                : maxDescriptionChars);
    }

    public String getDestinationTimeZone() {
        if (destCalendar != null) {
            return destCalendar.getTimeZone();
        }

        return "unknown";
    }

    public void setUsername(String value) {
        googleUsername = value;
    }

    public void setCalendarName(String value) {
        if (destinationCalendarName.equals(value)) {
            return;
        }

        destinationCalendarName = value;

        // Set our object to null to force a reconnect to the new calendar name
        destCalendar = null;
    }

    public void setSyncDescription(boolean value) {
        syncDescription = value;
    }

    public void setSyncAlarms(boolean value) {
        syncAlarms = value;
    }

    public void setSyncWhere(boolean value) {
        syncWhere = value;
    }

    public void setSyncAllSubjectsToValue(boolean value) {
        syncAllSubjectsToValue = value;
    }

    public void setSyncAllSubjectsToThisValue(String value) {
        syncAllSubjectsToThisValue = value;
    }

    public void setSyncMeetingAttendees(boolean value) {
        syncMeetingAttendees = value;
    }

    public void setMinStartDate(Date minStartDate) {
        this.minStartDate = minStartDate;
    }

    public void setMaxEndDate(Date maxEndDate) {
        this.maxEndDate = maxEndDate;
    }

    public void setDiagnosticMode(boolean value) {
        diagnosticMode = value;
    }

    public void setStatusMessageCallback(StatusMessageCallback value) {
        statusMessageCallback = value;
    }

    public void setClientSecretRegExFilename(String value) {
        clientSecretRegExFilename = value;
    }
}
