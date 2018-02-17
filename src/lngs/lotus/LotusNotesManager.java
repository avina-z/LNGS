// This source code is released under the GPL v3 license, http://www.gnu.org/licenses/gpl.html.
// This file is part of the LNGS project: http://sourceforge.net/projects/lngooglecalsync.
package lngs.lotus;

import lngs.util.LngsException;
import lngs.util.StatusMessageCallback;

import lotus.domino.*;

import java.io.*;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.util.*;

public class LotusNotesManager {
    protected StatusMessageCallback statusMessageCallback = null;
    String calendarViewName = "Google Calendar Sync";
    String password;
    String server;
    String mailfile;
    boolean requiresAuth;
    boolean diagnosticMode = false;
    String notesVersion;
    String serverDateFormat;

    // Our min and max dates for entries we will process.
    // If the calendar entry is outside this range, it is ignored.
    Date startDate = null;
    Date endDate = null;

    // These items are used when diagnosticMode = true
    File lnFoundEntriesFile = null;
    BufferedWriter lnFoundEntriesWriter = null;
    final String lnFoundEntriesFilename = "LotusNotesFoundEntries.txt";

    // Filename with full path
    String lnFoundEntriesFullFilename;
    File lnInRangeEntriesFile = null;
    BufferedWriter lnInRangeEntriesWriter = null;
    final String lnInRangeEntriesFilename = "LotusNotesInRangeEntries.txt";
    String lnInRangeEntriesFullFilename;

    public LotusNotesManager() {
        notesVersion = "unknown";

        // Get the absolute path to this app
        String appPath = new java.io.File("").getAbsolutePath() +
            System.getProperty("file.separator");
        lnFoundEntriesFullFilename = appPath + lnFoundEntriesFilename;
        lnInRangeEntriesFullFilename = appPath + lnInRangeEntriesFilename;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public void setMailFile(String mailfile) {
        this.mailfile = mailfile;
    }

    public void setRequiresAuth(boolean requiresAuth) {
        this.requiresAuth = requiresAuth;
    }

    public void setPassword(String password) {
        setRequiresAuth(true);
        this.password = password;
    }

    public void setDiagnosticMode(boolean value) {
        diagnosticMode = value;
    }

    public void setMinStartDate(Date minStartDate) {
        this.startDate = minStartDate;
    }

    public void setMaxEndDate(Date maxEndDate) {
        this.endDate = maxEndDate;
    }

    public void setServerDateFormat(String serverDateFormat) {
        this.serverDateFormat = serverDateFormat;
    }

    public void setStatusMessageCallback(StatusMessageCallback value) {
        statusMessageCallback = value;
    }

    public String getNotesVersion() {
        return notesVersion;
    }

    /**
     * Retrieve a list of Lotus Notes calendar entries.
     */
    public ArrayList<LotusNotesCalendarEntry> getCalendarEntries()
        throws LngsException, IOException {
        boolean wasNotesThreadInitialized = false;
        ArrayList<LotusNotesCalendarEntry> calendarEntries = new ArrayList<LotusNotesCalendarEntry>();
        LotusNotesCalendarEntry cal = null;

        statusMessageCallback.statusAppendStart(
            "Getting Lotus Notes calendar entries");

        loadNotesThreadClass();

        try {
            // Make sure your Windows PATH statement includes the location
            // for the Lotus main directory, e.g. "c:\Program Files\Lotus\Notes".
            // This is necessary because the Java classes call native/C dlls in
            // this directory.
            // If the dlls can't be found, then we will drop directly into
            // the finally section (no exception is thrown).  That's strange.
            // So, we set a flag to indicate whether things succeeded or not.
            NotesThread.sinitThread();
            wasNotesThreadInitialized = true;

            // Note: We cast null to a String to avoid overload conflicts
            Session session = NotesFactory.createSession((String) null,
                    (String) null, password);

            if (session == null) {
                throw new LngsException(
                    "Couldn't create Lotus Notes Session object.");
            }

            notesVersion = session.getNotesVersion();

            String dominoServerTemp = server;

            if (server.equals("")) {
                dominoServerTemp = null;
            }

            Database db = session.getDatabase(dominoServerTemp, mailfile, false);

            if (db == null) {
                // Strip off any path info from the mailfile and try to open the DB again
                int separatorIdx = mailfile.lastIndexOf(File.separator);

                if (separatorIdx > -1) {
                    String shortMailfile = mailfile.substring(separatorIdx + 1);
                    statusMessageCallback.statusAppendLineDiag(
                        "Using short mailfile name: " + shortMailfile);

                    // Open the DB a slightly different way
                    DbDirectory dbDir = session.getDbDirectory((String) null);
                    db = dbDir.openDatabase(shortMailfile);
                }
            }

            if (db == null) {
                throw new LngsException(
                    "Couldn't create Lotus Notes Database object.");
            }

            String strDateFormat;
            DateFormat dateFormat;

            if (serverDateFormat.isEmpty() ||
                    serverDateFormat.equalsIgnoreCase("detect")) {
                strDateFormat = getLotusServerDateFormat(session);
                dateFormat = new SimpleDateFormat(strDateFormat);
                statusMessageCallback.statusAppendLineDiag(
                    "Using Detected Server Date Format: " + strDateFormat);
            } else {
                dateFormat = new SimpleDateFormat(serverDateFormat);
                statusMessageCallback.statusAppendLineDiag(
                    "Using Explicit Server Date Format: " + serverDateFormat);
            }

            //DateFormat dfShort = DateFormat.getDateInstance(DateFormat.SHORT);
            //String strDateFormat = ((SimpleDateFormat)dfShort).toLocalizedPattern();
            //DateFormat dateFormat = dfShort;
            //statusMessageCallback.statusAppendLineDiag("Using Date Format: " + strDateFormat);

            // Query Lotus Notes to get calendar entries in our date range.
            // To understand this SELECT, go to http://publib.boulder.ibm.com/infocenter/domhelp/v8r0/index.jsp
            // and search for the various keywords. Here is an overview:
            //   !@IsAvailable($Conflict) will exclude entries that conflicts with another.
            //     LN doesn't show conflict entries and we should ignore them.
            //   @IsAvailable(CalendarDateTime) is true if the LN document is a calendar entry
            //   @Explode splits a string based on the delimiters ",; "
            //   The operator *= is a permuted equal operator. It compares all entries on
            //     the left side to all entries on the right side. If there is at least one
            //     match, then true is returned.
            String calendarQuery = "SELECT (!@IsAvailable($Conflict) & @IsAvailable(CalendarDateTime) & (@Explode(CalendarDateTime) *= @Explode(@TextToTime(\"" +
                dateFormat.format(startDate) + " - " +
                dateFormat.format(endDate) + "\"))))";

            //            Calendar calendar = Calendar.getInstance();   
            //            calendar.setTime(startDate);   
            //            String startDateFormula = "@Date(" + calendar.get(Calendar.YEAR) + ";" + (calendar.get(Calendar.MONTH) + 1) + ";" + calendar.get(Calendar.DATE) + "; 0; 0; 0)";
            //            calendar.setTime(endDate);   
            //            String endDateFormula = "@Date(" + calendar.get(Calendar.YEAR) + ";" + (calendar.get(Calendar.MONTH) + 1) + ";" + calendar.get(Calendar.DATE) + "; 23; 59; 59)";

            // 1/29/13 This query didn't work for Ray Wilson (compared to the current query).
            //String calendarQuery = "SELECT (!@IsAvailable($CSFlags) & @IsAvailable(CalendarDateTime) & (@Explode(CalendarDateTime) *= @Explode(@TextToTime(\"" +
            //dateFormat.format(startDate) + " - " + dateFormat.format(endDate) + "\")))) | " +
            //        "(@IsAvailable($CSFlags) & @IsAvailable(RepeatInstanceDates) & (@Explode(RepeatInstanceDates) *= @Explode(@TextToTime(\"" +
            //dateFormat.format(startDate) + " - " + dateFormat.format(endDate) + "\"))) & $CSFlags = \"c\")";

            //            String calendarQuery = "SELECT (@IsAvailable(CalendarDateTime) & (@Explode(CalendarDateTime) *>= @Explode(@Date(" +
            //                    (startDate.getYear()+1900) + ";" + (startDate.getMonth()+1) + ";" + startDate.getDate() + "))))";
            //            String calendarQuery = "SELECT (@IsAvailable(CalendarDateTime) & (@Explode(CalendarDateTime) *= @Explode(@Date(" +
            //                    (startDate.getYear()+1900) + ";" + (startDate.getMonth()+1) + ";" + startDate.getDate() + ") - @Date(2012;7;14))))";

            // This is the query I gave to Marcus. This returns more than we want, but it works.
            //            String calendarQuery = "SELECT (@IsAvailable(CalendarDateTime) & (@Explode(CalendarDateTime) *>= @Explode(" + 
            //                    "@Date(" + (startDate.getYear()+1900) + ";" + (startDate.getMonth()+1) + ";" + startDate.getDate() + "))) & " +
            //                    "(@Explode(CalendarDateTime) *<= @Explode(" + 
            //                    "@Date(" + (endDate.getYear()+1900) + ";" + (endDate.getMonth()+1) + ";" + endDate.getDate() + "))))";

            // This is the query I'm experimenting with:            
            //          String calendarQuery = "SELECT (@IsAvailable(CalendarDateTime) & (@Explode(CalendarDateTime)[1] >= " + 
            //                  "@Date(" + (startDate.getYear()+1900) + ";" + (startDate.getMonth()+1) + ";" + startDate.getDate() + ")))";
            statusMessageCallback.statusAppendLineDiag("Calendar query: " +
                calendarQuery);

            DocumentCollection queryResults = db.search(calendarQuery);

            if (queryResults == null) {
                statusMessageCallback.statusAppendLineDiag(
                    "Query results are null");

                return null;
            } else {
                statusMessageCallback.statusAppendLineDiag(
                    "Number of query results: " + queryResults.getCount());
            }

            if (diagnosticMode) {
                // Open the output file if it is not open
                if (lnFoundEntriesWriter == null) {
                    lnFoundEntriesFile = new File(lnFoundEntriesFullFilename);
                    lnFoundEntriesWriter = new BufferedWriter(new FileWriter(
                                lnFoundEntriesFile));
                    lnFoundEntriesWriter.write("Total entries: " +
                        queryResults.getCount() + "\n\n");
                }
            }

            calendarEntries = getCalendarEntryList(queryResults);

            return calendarEntries;
        } catch (NotesException ex) {
            String exMsg = "There was a problem reading Lotus Notes calendar entries." +
                "\nNotesException ID: " + ((NotesException) ex).id;

            if ((cal != null) && (cal.getSubject() != null)) {
                throw new LngsException(exMsg +
                    "\nThe subject of the entry being processed: " +
                    cal.getSubject(), ex);
            } else {
                throw new LngsException(exMsg, ex);
            }
        } catch (Exception ex) {
            throw new LngsException("There was a problem reading Lotus Notes calendar entries.",
                ex);
        } finally {
            // If true, the NotesThread failed to init. The LN dlls probably weren't found.
            // NOTE: Make sure this check is the first line in the finally block. When the
            // init fails, some of the finally block may get skipped.
            if (!wasNotesThreadInitialized) {
                throw new LngsException(
                    "There was a problem initializing the Lotus Notes thread.\nMake sure the Lotus dll/so/dylib directory is in your path.\nAlso look at the Troubleshooting section of the Help file.");
            }

            if (diagnosticMode) {
                writeInRangeEntriesToFile(calendarEntries);
            }

            if (lnFoundEntriesWriter != null) {
                lnFoundEntriesWriter.close();
                lnFoundEntriesWriter = null;
            }

            NotesThread.stermThread();

            statusMessageCallback.statusAppendFinished();
        }
    }

    /**
     * Manually load the Lotus Notes thread class to see if it can be found.
     */
    protected void loadNotesThreadClass() throws LngsException {
        try {
            // Some users, especially on OS X, have trouble locating Notes.jar (which
            // needs to be in the classpath) and the supporting dll/so/dylib files (which
            // need to be in the path/ld_library_path).  Try to load one of the Lotus
            // classes to make sure we can find Notes.jar.
            // The next try/catch block (with the sinitThread() call) will check if
            // the supporing libs can be found.
            ClassLoader.getSystemClassLoader()
                       .loadClass("lotus.domino.NotesThread");
        } catch (ClassNotFoundException ex) {
            throw new LngsException("The Lotus Notes Java interface file (Notes.jar) could not be found.\nMake sure Notes.jar is in your classpath.",
                ex);
        }
    }

    /**
     * Return the date format used on the Domino server.
     */
    protected String getLotusServerDateFormat(Session session)
        throws NotesException {
        // Get our start and end query dates in Lotus Notes format. We will query
        // using the localized format for the dates (which is what Lotus expects).
        // E.g. in England the date may be 31/1/2011, but in the US it is 1/31/2011.
        String strDateFormat;

        // Get the date separator used on the Domino server, e.g. / or -
        String dateSep = session.getInternational().getDateSep();

        // Determine if the server date format is DMY, YMD, or MDY
        if (session.getInternational().isDateDMY()) {
            strDateFormat = "dd" + dateSep + "MM" + dateSep + "yyyy";
        } else if (session.getInternational().isDateYMD()) {
            strDateFormat = "yyyy" + dateSep + "MM" + dateSep + "dd";
        } else {
            strDateFormat = "MM" + dateSep + "dd" + dateSep + "yyyy";
        }

        return strDateFormat;
    }

    /**
     * Process a list of Lotus Notes entries returned from a query.
     * Return a list of LotusNotesCalendarEntry objects.
     */
    protected ArrayList<LotusNotesCalendarEntry> getCalendarEntryList(
        DocumentCollection queryResults)
        throws LngsException, NotesException, IOException {
        boolean keepCalEntry = false;
        boolean isRepeating = false;
        ArrayList<LotusNotesCalendarEntry> calendarEntries = new ArrayList<LotusNotesCalendarEntry>();
        LotusNotesCalendarEntry cal = null;

        Document doc = queryResults.getFirstDocument();

        int cntEntry = 1;

        try {
            // Loop through all entries returned
            while (doc != null) {
                Item lnItem;
                keepCalEntry = true;

                // If we are in diagnostic mode, write the entry to a text file
                if (diagnosticMode) {
                    writeEntryToFile(doc, cntEntry);
                }

                cal = new LotusNotesCalendarEntry();

                lnItem = doc.getFirstItem("Subject");

                if (!isItemEmpty(lnItem)) {
                    cal.setSubject(lnItem.getText());
                } else {
                    cal.setSubject("<no subject>");
                }

                lnItem = doc.getFirstItem("Body");

                if (!isItemEmpty(lnItem)) {
                    cal.setBody(lnItem.getText());
                }

                // Get the type of Lotus calendar entry
                lnItem = doc.getFirstItem("Form");

                if (!isItemEmpty(lnItem)) {
                    cal.setEntryType(lnItem.getText());
                } else {
                    // Assume we have an appointment
                    cal.setEntryType(LotusNotesCalendarEntry.EntryType.APPOINTMENT);
                }

                if (cal.getEntryType() == LotusNotesCalendarEntry.EntryType.APPOINTMENT) {
                    lnItem = doc.getFirstItem("AppointmentType");

                    if (!isItemEmpty(lnItem)) {
                        cal.setAppointmentType(lnItem.getText());
                    }
                }

                lnItem = doc.getFirstItem("Room");

                if (!isItemEmpty(lnItem)) {
                    cal.setRoom(lnItem.getText());
                }

                lnItem = doc.getFirstItem("Location");

                if (!isItemEmpty(lnItem)) {
                    cal.setLocation(lnItem.getText());
                }

                lnItem = doc.getFirstItem("STUnyteConferenceURL");

                if (!isItemEmpty(lnItem)) {
                    cal.setSTUnyteConferenceURL(lnItem.getText());
                }

                lnItem = doc.getFirstItem("$Alarm");

                if (!isItemEmpty(lnItem)) {
                    cal.setAlarm(true);
                    lnItem = doc.getFirstItem("$AlarmOffset");

                    if (!isItemEmpty(lnItem)) {
                        String alarmValue = lnItem.getText();
                        // Handle some locales that use ',' as the decimal point
                        alarmValue = alarmValue.replace(',', '.');
                        // In rare instances, a min-offset reminder can become a floating
                        // point value, e.g. create an alarm with a 95 min reminder.
                        // This is recalculated to ‘1.58333333333333 Hours’ and then
                        // the inaccurate ‘94.9999999999998 minutes’.
                        cal.setAlarmOffsetMins(Math.round(Float.parseFloat(
                                    alarmValue)));
                    }
                }

                // When the Mark Private checkbox is checked, OrgConfidential is set to 1
                lnItem = doc.getFirstItem("OrgConfidential");

                if (!isItemEmpty(lnItem)) {
                    if (lnItem.getText().equals("1")) {
                        cal.setPrivate(true);
                    }
                }

                //Get attendee info
                lnItem = doc.getFirstItem("REQUIREDATTENDEES");

                if (!isItemEmpty(lnItem)) {
                    cal.setRequiredAttendees(lnItem.getText());
                }

                lnItem = doc.getFirstItem("OPTIONALATTENDEES");

                if (!isItemEmpty(lnItem)) {
                    cal.setOptionalAttendees(lnItem.getText());
                }

                lnItem = doc.getFirstItem("CHAIR");

                if (!isItemEmpty(lnItem)) {
                    cal.setChairperson(lnItem.getText());
                }

                // Get unique identifier for this entry
                lnItem = doc.getFirstItem("APPTUNID");

                if (!isItemEmpty(lnItem)) {
                    // If the APPTUNID contains a URL (http or https), then the entry
                    // isn't a standard Lotus Notes item. It is a link to an external calendar.
                    // In this case, we want to ignore the entry.
                    if (lnItem.getText().matches("(?i).*(https?|Notes):.*")) {
                        keepCalEntry = false;
                    }
                }

                cal.setModifiedDateTime(doc.getLastModified().toJavaDate());

                if (keepCalEntry) {
                    // StartDateTime will either contain a single date:
                    //     12/01/2014 11:00:00 AM
                    // or be a repeating entry like this:
                    //     12/01/2014 11:00:00 AM;12/02/2014 11:00:00 AM;12/03/2014 11:00:00 AM;12/04/2014 11:00:00 AM
                    Item lnStartDateTime = doc.getFirstItem("StartDateTime");
                    isRepeating = false;

                    if (isItemEmpty(lnStartDateTime)) {
                        // To Do entries may not have a StartDateTime, but should always have a CalendarDateTime
                        lnStartDateTime = doc.getFirstItem("CalendarDateTime");

                        if (isItemEmpty(lnStartDateTime)) {
                            throw new LngsException(
                                "Couldn't determine start date for Lotus Notes entry.");
                        }
                    } else {
                        if (lnStartDateTime.getText().contains(";")) {
                            isRepeating = true;
                        }
                    }

                    Item lnEndDateTime = doc.getFirstItem("EndDateTime");

                    if (isRepeating) {
                        // Handle Lotus Notes repeating entries by creating multiple Google entries
                        Vector startDates = null;
                        Vector endDates = null;

                        if (!isItemEmpty(lnStartDateTime)) {
                            if (lnStartDateTime.getType() == Item.DATETIMES) {
                                startDates = lnStartDateTime.getValues();
                            } else {
                                throw new LngsException(
                                    "Repeating start dates are not of type DATETIMES.");
                            }
                        }

                        if (!isItemEmpty(lnEndDateTime)) {
                            if (lnEndDateTime.getType() == Item.DATETIMES) {
                                endDates = lnEndDateTime.getValues();
                            } else {
                                throw new LngsException(
                                    "Repeating end dates are not of type DATETIMES.");
                            }
                        } else {
                            statusMessageCallback.statusAppendLineDiag(
                                "Couldn't determine end date. Using start date instead. Subject: " +
                                cal.getSubject());
                            endDates = startDates;
                        }

                        for (int i = 0; i < startDates.size(); i++) {
                            if (startDates.get(i) instanceof DateTime) {
                                DateTime notesDate = (DateTime) startDates.get(i);
                                Date javaDate = notesDate.toJavaDate();

                                // Only add the entry if it is within our sync date range
                                if (isDateInRange(javaDate)) {
                                    // We are creating multiple entries from one repeating entry.
                                    // We use the same Lotus UID for all entries because we will
                                    // prepend another GUID before inserting into Google.
                                    cal.setUID(doc.getUniversalID());

                                    cal.setStartDateTime(javaDate);

                                    if (endDates != null) {
                                        notesDate = (DateTime) endDates.get(i);
                                        cal.setEndDateTime(notesDate.toJavaDate());
                                    }

                                    calendarEntries.add(cal.clone());
                                } else {
                                    DateFormat dfShort = DateFormat.getDateInstance(DateFormat.SHORT);
                                }
                            }
                        }
                    } else {
                        cal.setUID(doc.getUniversalID());

                        if (!isItemEmpty(lnStartDateTime)) {
                            cal.setStartDateTime(lnStartDateTime.getDateTimeValue()
                                                                .toJavaDate());
                        }

                        if (isItemEmpty(lnEndDateTime)) {
                            // For To Do tasks, the EndDateTime doesn't exist. There is usually (but not always) an EndDate.
                            // If EndDate is also missing, then set the end date equal to start date.
                            lnEndDateTime = doc.getFirstItem("EndDate");

                            if (isItemEmpty(lnEndDateTime)) {
                                lnEndDateTime = lnStartDateTime;
                            }
                        }

                        if (!isItemEmpty(lnEndDateTime)) {
                            cal.setEndDateTime(lnEndDateTime.getDateTimeValue()
                                                            .toJavaDate());
                        } else {
                            throw new LngsException(
                                "Couldn't determine end date for Lotus Notes entry.");
                        }

                        // Only add the entry if it is within our sync date range
                        if (isDateInRange(cal.getStartDateTime())) {
                            calendarEntries.add(cal);
                        }
                    }
                }

                doc = queryResults.getNextDocument();
                cntEntry++;
            }
        } catch (Exception ex) {
            String exMsg = "There was a problem processing the retrieved Lotus Notes calendar entries.";

            if ((cal != null) && (cal.getSubject() != null)) {
                throw new LngsException(exMsg +
                    "\nThe subject of the entry being processed: " +
                    cal.getSubject(), ex);
            } else {
                throw new LngsException(exMsg, ex);
            }
        }

        return calendarEntries;
    }

    /**
     * Try to detect some Lotus Notes settings and return them.
     */
    public LotusNotesSettings detectLotusSettings(String lnPassword)
        throws LngsException, NotesException {
        boolean wasNotesThreadInitialized = false;
        String s = "";
        LotusNotesSettings lns = new LotusNotesSettings();

        try {
            NotesThread.sinitThread();
            wasNotesThreadInitialized = true;

            Session session = NotesFactory.createSession((String) null,
                    (String) null, lnPassword);

            lns.setMailFile(session.getEnvironmentString("MailFile", true));

            // The mail server will probably have the format "CN=MY-LN-SERVER/OU=SRV/O=AcmeCo".
            // Get only the value after "CN=" and before the first "/".
            String mailServer = session.getEnvironmentString("MailServer", true);
            int i = mailServer.indexOf("/");

            if ((i > 0) && mailServer.substring(0, 3).equals("CN=")) {
                mailServer = mailServer.substring(3, i);
            }

            lns.setServerName(mailServer);

            lns.hasLocalServer = true;

            // Try to connect to the local Domino server.
            Database db = session.getDatabase(null, lns.getMailFile(), false);

            if (db == null) {
                lns.hasLocalServer = false;
            }
        } catch (NotesException ex) {
            throw ex;
        } finally {
            // If true, the NotesThread failed to init. The LN dlls probably weren't found.
            // NOTE: Make sure this check is the first line in the finally block. When the
            // init fails, some of the finally block may get skipped.
            if (!wasNotesThreadInitialized) {
                throw new LngsException(
                    "There was a problem initializing the Lotus Notes thread.\nMake sure the Lotus dll/so/dylib directory is in your path.\nAlso look at the Troubleshooting section of the Help file.");
            }

            NotesThread.stermThread();
        }

        return lns;
    }

    /**
     * Determine if the calendar entry date is in the range of dates to be processed.
     * @param entryDate - The calendar date to inspect.
     * @return True if the date is in the range, false otherwise.
     */
    public boolean isDateInRange(Date entryDate) {
        if ((entryDate != null) && entryDate.after(startDate) &&
                entryDate.before(endDate)) {
            return true;
        }

        return false;
    }

    /**
     * Write all the items in a Lotus Notes Document (aka calendar entry) to a text file.
     * @param doc - The Document to process.
     * @param docNumber - The sequential number for this document.
     */
    public void writeEntryToFile(lotus.domino.Document doc, int docNumber)
        throws IOException, NotesException {
        // Add the items to a list so we can sort them later
        List<String> itemsAndValues = new ArrayList<String>();

        lnFoundEntriesWriter.write("=== Calendar Entry " + docNumber +
            " ===\n");

        if (doc.isDeleted()) {
            lnFoundEntriesWriter.write("  Doc is flagged Deleted.\n\n");

            return;
        }

        if (doc.isSigned()) {
            lnFoundEntriesWriter.write("  Doc is flagged Signed.\n\n");

            return;
        }

        itemsAndValues.add("  LastModified (from getLastModified()): " +
            doc.getLastModified() + "\n");
        itemsAndValues.add("  UniversalID (from getUniversalID()): " +
            doc.getUniversalID() + "\n");

        String itemName;

        // Loop through each item
        for (Object itemObj : doc.getItems()) {
            if (itemObj instanceof Item) {
                itemName = ((Item) itemObj).getName();
            } else {
                continue;
            }

            // Get the item value using the item name
            Item lnItem = doc.getFirstItem(itemName);

            if (lnItem != null) {
                itemsAndValues.add("  " + itemName + ": " + lnItem.getText() +
                    "\n");
            }
        }

        Collections.sort(itemsAndValues, String.CASE_INSENSITIVE_ORDER);

        for (String itemStr : itemsAndValues) {
            lnFoundEntriesWriter.write(itemStr);
        }

        lnFoundEntriesWriter.write("\n\n");
    }

    public void writeInRangeEntriesToFile(
        List<LotusNotesCalendarEntry> calendarEntries)
        throws IOException {
        try {
            lnInRangeEntriesFile = new File(lnInRangeEntriesFullFilename);
            lnInRangeEntriesWriter = new BufferedWriter(new FileWriter(
                        lnInRangeEntriesFile));

            if (calendarEntries == null) {
                lnInRangeEntriesWriter.write(
                    "The calendar entries list is null.\n");
            } else {
                lnInRangeEntriesWriter.write("Total entries: " +
                    calendarEntries.size() + "\n\n");
            }

            for (LotusNotesCalendarEntry entry : calendarEntries) {
                lnInRangeEntriesWriter.write("=== " + entry.getSubject() +
                    "\n");
                lnInRangeEntriesWriter.write("  UID: " + entry.getUID() + "\n");
                lnInRangeEntriesWriter.write("  Start Date:    " +
                    entry.getStartDateTime() + "\n");
                lnInRangeEntriesWriter.write("  End Date:      " +
                    entry.getEndDateTime() + "\n");
                lnInRangeEntriesWriter.write("  Modified Date: " +
                    entry.getModifiedDateTime() + "\n");
                lnInRangeEntriesWriter.write("  Location: " +
                    entry.getLocation() + "\n");
                lnInRangeEntriesWriter.write("  Online Meeting: " +
                        entry.getSTUnyteConferenceURL() + "\n");
                lnInRangeEntriesWriter.write("  Room: " + entry.getRoom() +
                    "\n");
                lnInRangeEntriesWriter.write("  Alarm: " + entry.getAlarm() +
                    "\n");
                lnInRangeEntriesWriter.write("  Alarm Offset Mins: " +
                    entry.getAlarmOffsetMins() + "\n");
                lnInRangeEntriesWriter.write("  Appointment Type: " +
                    entry.getAppointmentType() + "\n");
                lnInRangeEntriesWriter.write("  Entry Type: " +
                    entry.getEntryType() + "\n");
                lnInRangeEntriesWriter.write("  Description: |" +
                    entry.getBody() + "|\n");
                lnInRangeEntriesWriter.write("\n");
            }
        } catch (IOException ex) {
            throw ex;
        } finally {
            if (lnInRangeEntriesWriter != null) {
                lnInRangeEntriesWriter.close();
                lnInRangeEntriesWriter = null;
            }
        }
    }

    /**
     * Returns true if the Lotus Notes Item object is empty or null.
     * @param lnItem The object to inspect.
     */
    protected boolean isItemEmpty(Item lnItem) {
        try {
            // Lotus Notes Item objects are usually read by name, e.g. lnItem = doc.getFirstItem("Subject").
            // If the name doesn't exist at all then null is returned.
            // If the name does exist, but doesn't have a value, then lnItem.getText() returns "".
            // Check for both conditions.
            if ((lnItem == null) ||
                    ((lnItem != null) && lnItem.getText().isEmpty())) {
                return true;
            }
        } catch (NotesException ex) {
            // An error means we couldn't read the Item, so consider it empty
            return true;
        }

        return false;
    }

    public class LotusNotesSettings {
        private String mailFile;
        private String serverName;
        public boolean hasLocalServer;

        public String getMailFile() {
            return mailFile;
        }

        public void setMailFile(String mailFile) {
            this.mailFile = mailFile;
        }

        public String getServerName() {
            return serverName;
        }

        public void setServerName(String serverName) {
            this.serverName = serverName;
        }
    }
}
