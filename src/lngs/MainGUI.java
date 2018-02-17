// Original code by Shin Sterneck. Code maintained and
// significantly modified by Dean Hill.
//
// This file is part of the Lotus Notes to Google Calendar Synchronizer application.
//
//    This application is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This application is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this application.  If not, see <http://www.gnu.org/licenses/>.


package lngs;

import com.google.api.services.calendar.model.Event;
import lngs.util.StatusMessageCallback;
import lngs.util.ProxyManager;
import lngs.lotus.LotusNotesManager;
import lngs.lotus.LotusNotesCalendarEntry;
import lngs.google.GoogleManager;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.*;
import java.awt.Cursor;
import java.awt.TrayIcon;
import java.awt.SystemTray;
import java.awt.Image;
import java.awt.PopupMenu;
import java.awt.MenuItem;
import java.awt.AWTException;
import java.awt.event.*;
import java.math.BigDecimal;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.*;
import lngs.util.ConfigurationManager;
import lngs.util.LngsException;
import lotus.domino.NotesException;


public class MainGUI extends javax.swing.JFrame implements StatusMessageCallback {
    public static void main(String args[]) {
        if (args.length == 0) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    lngs.MainGUI gui = new MainGUI();
                    if (exitCode != ExitCodes.SUCCESS)
                        System.exit(exitCode.ordinal());
                        
                    gui.setupSystemTray();
                    gui.setLocationRelativeTo(null);  // Center window on primary monitor
                    gui.jButton_Synchronize.requestFocus();
                }
            });
        } else if (args[0].equals("-silent")) {
            // Run in "silent" command-line mode
            new MainGUI().runCommandLine();
            System.exit(exitCode.ordinal());
        } else {
            System.out.println("Usage: mainGUI <options>\n\tIf no options are specified, then the application starts in GUI mode.\n\t-silent  Performs synchronization with existing settings in non-GUI mode.");
            System.exit(ExitCodes.INVALID_PARM.ordinal());
        }
    }

    public MainGUI() {
        try {
            preInitComponents();
            initComponents();

            // Set the application icon
            URL urlIcon = getClass().getResource(iconAppFullPath);
            if (urlIcon == null) {
                System.out.println("The program icon could not be found at this resource path: " + iconAppFullPath);
                System.exit(ExitCodes.MISSING_RESOURCE.ordinal());
            }
            iconApp = new ImageIcon(urlIcon);
            setIconImage(iconApp.getImage());

            syncScheduleListener = new SyncScheduleListener();
            // Set the timer delay to a large dummy value because we don't know an actual value yet
            syncTimer = new javax.swing.Timer(600000, syncScheduleListener);
            syncTimer.setCoalesce(true);
            syncTimer.setRepeats(true);
            syncTimer.stop();

            // Initialize proxy manager
            proxyMgr = new ProxyManager();

            // Load configuration bean
            configMgr = new ConfigurationManager();
            configMgr.readConfig();

            loadSettings();

            validate();

            // Check whether the loaded configuration meets our requirements to sync
            validateSettings();

            setDateRange();

            // Get the path to the currently running class file
            helpFilename = getClass().getResource(getClass().getSimpleName() + ".class").getPath();
            int slashIdx;
            // If the class is in a jar, then the jar filename is in the path and we want the filename removed
            int jarIdx = helpFilename.lastIndexOf(".jar!");
            if (jarIdx == -1)
                slashIdx = helpFilename.lastIndexOf("/");
            else
                slashIdx = helpFilename.lastIndexOf("/", jarIdx);

            helpFilename = helpFilename.substring(0, slashIdx+1) + "HelpFile.html";

            // If this is a new version, then make the window visible by default because we
            // show a new-version message later
            if ( ! configMgr.getApplicationVersion().equals(appVersion) || jCheckBox_dontSaveSensitiveData.isSelected()) {
                setVisible(true);
                setExtendedState(NORMAL);

                if ( jCheckBox_dontSaveSensitiveData.isSelected()) {
                    promptForPasswords();
                }
            }
        } catch (IOException ex) {
            exitCode = ExitCodes.EXCEPTION;
            showException(ex);
        }        
    }

    private void preInitComponents() {
        Exception caughtEx = null;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (InstantiationException ex) {
            caughtEx = ex;
        } catch (IllegalAccessException ex) {
            caughtEx = ex;
        } catch (UnsupportedLookAndFeelException ex) {
            caughtEx = ex;
        } catch (ClassNotFoundException ex) {
            caughtEx = ex;
        } finally {
            if (caughtEx != null) {
                Logger.getLogger(MainGUI.class.getName()).log(Level.SEVERE, "There was an error setting the window's look-and-feel.", caughtEx);            
            }
        }
    }

    protected void setupSystemTray() {
        if (SystemTray.isSupported()) {
            // Get the SystemTray instance
            SystemTray tray = SystemTray.getSystemTray();

            // Load an image
            Image image = iconApp.getImage();

            // Create a popup menu
            PopupMenu popup = new PopupMenu();

            // Create a listener for the default action executed on the tray icon
            ActionListener listenerOpen = new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    // This action will make the window visible and restore from the minimized state
                    setVisible(true);
                    setExtendedState(NORMAL);
                }
            };
            // Create menu item for the default action (open window)
            MenuItem defaultItem = new MenuItem("Open");
            defaultItem.addActionListener(listenerOpen);
            popup.add(defaultItem);

            // Create a listener for showing the application and syncing
            ActionListener listenerSync = new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    // Select the first tab
                    jTabbedPane_Main.setSelectedIndex(TabIds.SYNC.ordinal());
                    setVisible(true);
                    setExtendedState(NORMAL);
                    jButton_SynchronizeActionPerformed(null);
                }
            };
            MenuItem syncItem = new MenuItem("Open and Sync");
            syncItem.addActionListener(listenerSync);
            popup.add(syncItem);

            // Create a listener for exiting the application
            ActionListener listenerExit = new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    formWindowClosed(null);
                }
            };
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(listenerExit);
            popup.add(exitItem);

            // Construct a TrayIcon
            trayIcon = new TrayIcon(image, "Lotus Notes Google Sync", popup);
            trayIcon.setImageAutoSize(true);
            // Set the TrayIcon properties
            trayIcon.addActionListener(listenerOpen);

            // Add the tray image
            try {         
                tray.add(trayIcon);
            }     
            catch (AWTException e) {
                System.err.println(e);
            }     
        } else {
            // The system tray is not available so just make the GUI visible and minimized
            setVisible(true);
            formWindowIconified(null);
        }
    }


    /**
     * Runs the synchronization in silent mode using existing configuration settings.
     */
    public void runCommandLine(){
        // Make sure the GUI is hidden
        this.setVisible(false);
        isSilentMode = true;
        doSync();
    }


    /**
     * Perform synchronization independent of GUI or non-GUI mode.
     */
    public void doSync() {
        long startTime = 0L;
        DateFormat dfShort = DateFormat.getDateInstance(DateFormat.SHORT);
        DateFormat tfDefault = DateFormat.getTimeInstance();
        
        Exception caughtEx = null;
        try {
            if (!jButton_Synchronize.isEnabled()) {
                // The Sync button is disabled. This probably means some config settings
                // were invalid and we don't want to allow syncing.
                return;
            }

            startTime = System.currentTimeMillis();

            proxyMgr.deactivateNow();

            statusBarSet("Performing sync...");
            statusClear();
            setDateRange();

            String strNow = dfShort.format(new Date()) + " " + tfDefault.format(new Date());
            if (configMgr.getSyncOnStartup())
                statusAppendLine("Automatic sync-on-startup is enabled. Starting sync - " + strNow);
            else
                statusAppendLine("Starting sync - " + strNow);

            // Don't echo the commented out values for privacy reasons
            statusAppendLineDiag("Application Version: " + appVersion);
            statusAppendLineDiag("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
            statusAppendLineDiag("Java: " + System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
            statusAppendLineDiag("Java Home: " + System.getProperty("java.home"));
            statusAppendLineDiag("Java Classpath: " + System.getProperty("java.class.path"));
            statusAppendLineDiag("Java Library Path: " + System.getProperty("java.library.path"));
            statusAppendLineDiag("Local Server: " + jCheckBox_LotusNotesServerIsLocal.isSelected());
            statusAppendLineDiag("Destination Calendar: " + jTextField_DestinationCalendarName.getText());
            statusAppendLineDiag("Local Date Format: " + ((SimpleDateFormat)dfShort).toLocalizedPattern());
            statusAppendLineDiag("Server Date Format: " + jTextField_LotusNotesServerDateFormat.getText());
            statusAppendLineDiag("Use Proxy: " + jCheckBox_enableProxy.isSelected());
            statusAppendLineDiag("Use Proxy Username: " + (!jTextField_proxyUsername.getText().isEmpty()));
            statusAppendLineDiag("Sync On Startup: " + jCheckBox_SyncOnStart.isSelected());
            statusAppendLineDiag("Sync All Subjects To Value: " + jCheckBox_SyncAllSubjectsToValue.isSelected());
            statusAppendLineDiag("Sync Location and Room: " + jCheckBox_SyncLocationAndRoom.isSelected());
            statusAppendLineDiag("Sync Description: " + jCheckBox_SyncDescription.isSelected());
            statusAppendLineDiag("Sync Alarms: " + jCheckBox_SyncAlarms.isSelected());
            statusAppendLineDiag("Sync Days In Past: " + jTextField_SyncDaysInPast.getText());
            statusAppendLineDiag("Sync Days In Future: " + jTextField_SyncDaysInFuture.getText());

            

//The loaded JSSE trust keystore location
//statusAppendLineDiag("Java Trust Store: " + System.getProperty("javax.net.ssl.trustStore"));
//The loaded JSSE trust keystore type
//statusAppendLineDiag("Java Trust Store Type: " + System.getProperty("javax.net.ssl.trustStoreType"));
//The JSSE trust keystore provider & encrypted password
//statusAppendLineDiag("Java Trust Store Provider: " + System.getProperty("javax.net.ssl.trustStoreProvider"));
//statusAppendLineDiag("Java Trust Store Password: " + System.getProperty("javax.net.ssl.trustStorePassword"));


            
            
//if (true) {statusAppendLineDiag("DEBUG: Done echoing values. Stopping sync."); return;}

            statusAppendLine("Date range: " + dfShort.format(startDate) + " thru " + dfShort.format(endDate) + " (-" + syncDaysInPast +  " to +" + syncDaysInFuture + " days)");

            // === Check for Client ID file ===
            String clientIdFilename = googleMgr.getClientIdFilename();              
            if (clientIdFilename.isEmpty()) {
                statusAppendLine("\n=== ERROR ===\nA Google Client ID file could not be found." +
                        "\nRead the Installation instructions in the Help File \nto learn how to create a Client ID file.\n");
                return;
            }
            
            // === Get the Lotus Notes calendar data
            lotusNotesMgr.setStatusMessageCallback(this);

            lotusNotesMgr.setRequiresAuth(true);
            lotusNotesMgr.setPassword(new String(jPasswordField_LotusNotesPassword.getPassword()));
            String lnServer = jTextField_LotusNotesServer.getText();
            if (jCheckBox_LotusNotesServerIsLocal.isSelected())
                lnServer = "";
            lotusNotesMgr.setServer(lnServer);
            lotusNotesMgr.setServerDateFormat(jTextField_LotusNotesServerDateFormat.getText());
            lotusNotesMgr.setMailFile(jTextField_LotusNotesMailFile.getText());
            lotusNotesMgr.setMinStartDate(startDate);
            lotusNotesMgr.setMaxEndDate(endDate);
            lotusNotesMgr.setDiagnosticMode(jCheckBox_DiagnosticMode.isSelected());

            ArrayList<LotusNotesCalendarEntry> lotusCalEntries = lotusNotesMgr.getCalendarEntries();
            statusAppendLine(lotusCalEntries.size() + " Lotus entries found within date range");

            statusAppendLineDiag("Lotus Version: " + lotusNotesMgr.getNotesVersion());

//if (true) {statusAppendLineDiag("DEBUG: Lotus Notes tasks finished. Stopping sync."); return;}

            // === Copy the Lotus Notes data to Google calendar

            if (jCheckBox_enableProxy.isSelected()) {
                if (! jTextField_proxyUsername.getText().isEmpty()) {
                    proxyMgr.enableProxyAuthentication(true);
                    proxyMgr.setProxyUser(jTextField_proxyUsername.getText());
                    proxyMgr.setProxyPassword(new String(jPasswordField_proxyPassword.getPassword()));
                }

                proxyMgr.setProxyHost(jTextField_proxyIP.getText());
                proxyMgr.setProxyPort(jTextField_proxyPort.getText());
                    
                proxyMgr.activateNow();

//statusAppendLineDiag("DEBUG Proxy manually set info  host: " + proxy.getProxyHost() + "   port: " + proxy.getProxyPort() + "   user: " + proxy.getProxyUser() + "   pwd: " + proxy.getProxyPassword());
            }

            googleMgr.setStatusMessageCallback(this);
            googleMgr.setUsername(jTextField_GoogleUsername.getText());
            googleMgr.setCalendarName(jTextField_DestinationCalendarName.getText());
//            googleMgr.setUseSSL(GoogleConnectUsingSSL);
            googleMgr.setDiagnosticMode(jCheckBox_DiagnosticMode.isSelected());
            googleMgr.setSyncDescription(jCheckBox_SyncDescription.isSelected());
            googleMgr.setSyncAlarms(jCheckBox_SyncAlarms.isSelected());
            googleMgr.setSyncWhere(jCheckBox_SyncLocationAndRoom.isSelected());
            googleMgr.setSyncAllSubjectsToValue(jCheckBox_SyncAllSubjectsToValue.isSelected());
            googleMgr.setSyncAllSubjectsToThisValue(jTextField_SyncAllSubjectsToThisValue.getText());
            googleMgr.setSyncMeetingAttendees(jCheckBox_SyncMeetingAttendees.isSelected());
            googleMgr.setMinStartDate(startDate);
            googleMgr.setMaxEndDate(endDate);

            googleMgr.connect();

//if (true) {statusAppendLineDiag("DEBUG: Done logging into Google. Stopping sync."); return;}

            ArrayList<Event> googleCalEntries = googleMgr.getCalendarEntries();

            statusAppendLine(googleCalEntries.size() + " Google entries found within date range");

            statusAppendStart("Comparing Lotus Notes and Google calendar entries");
            googleMgr.compareCalendarEntries(lotusCalEntries, googleCalEntries);
            statusAppendFinished();
            statusAppendLine(lotusCalEntries.size() + " Google entries to create. " + googleCalEntries.size() + " entries to delete.");

//googleService.createSampleGEntry();
//if (true) {statusAppendLineDiag("DEBUG: Done comparing entries. Stopping sync."); return;}

            if (googleCalEntries.size() > 0) {
                statusAppendStart("Deleting old Google calendar entries");
                int deleteCount = googleMgr.deleteCalendarEntries(googleCalEntries);
                statusAppendFinished();
                statusAppendLine(deleteCount + " Google entries deleted");
            }

            if (lotusCalEntries.size() > 0) {
                statusAppendStart("Creating new Google calendar entries");
                int createdCount = 0;
                createdCount = googleMgr.createCalendarEntries(lotusCalEntries);
                statusAppendFinished();
                statusAppendLine(createdCount + " Google entries created");
            }
        } catch (IOException ex) {
            caughtEx = ex;
        } catch (InterruptedException ex) {
            caughtEx = ex;
        } catch (LngsException ex) {
            caughtEx = ex;
        }
        finally {
            if (caughtEx != null) {
                statusAppendException("There was an error synchronizing.\nSee Troubleshooting in the Help file.\nThis screen output is also in " + logFilename + ".", caughtEx);
                statusBarSetWarning("Sync failed");
                if (!isShowing()) {
                    trayIcon.displayMessage("LNGS", "Sync failed. Open the application or " + logFilename + " for more information.", TrayIcon.MessageType.ERROR);   
                }
            } else {
                statusBarSet("Sync success");
            }

            long elapsedMillis = System.currentTimeMillis() - startTime;
            BigDecimal elapsedSecs = new BigDecimal(elapsedMillis / 1000.0).setScale(1, BigDecimal.ROUND_HALF_UP);
            statusAppendLine("Finished sync (" + elapsedSecs + " s total) - " + dfShort.format(new Date()) + " " + tfDefault.format(new Date()));

            if (!isSilentMode)
            {
                try {
                    BufferedWriter fileOut = new BufferedWriter(new FileWriter(logFullPath));
                    jTextArea_Status.write(fileOut);
                    fileOut.close();
                } catch (IOException ex) {
                    statusAppendException("There was an error saving to " + logFullPath + ".", ex);
                }
            }
        }
    }


    protected void showNewVersionMessage() {
        lngs.NewVersionDialog nvd = new NewVersionDialog(this, false);
        nvd.SetAppVersion(appVersion);
        nvd.SetHelpFilename(helpFilename);
        nvd.setVisible(true);

        // Update the version number in the config file so this message won't be shown again
        configMgr.setApplicationVersion(appVersion);
        
        try {
            saveSettings();
        } catch (IOException ex) {
            statusAppendException("There was an error saving settings.", ex);
        }
    }

    protected void promptForPasswords() {
        // Make sure the Connection Settings tab is the active tab
        jTabbedPane_Main.setSelectedIndex(TabIds.CONNECTION_SETTINGS.ordinal());
        
        // If the "don't save" checkbox is unchecked, just clear the status message
        if (!jCheckBox_dontSaveSensitiveData.isSelected()) {
            statusBarSet("");
            return;
        }

        String passwordMsg = "";
        if (jPasswordField_LotusNotesPassword.getPassword().length == 0) {
            // A Lotus Notes password is needed. Build the appropriate user message.
            passwordMsg = "Enter a Lotus Notes password.";
            jPasswordField_LotusNotesPassword.requestFocus();
        }
        
        if (jCheckBox_enableProxy.isSelected()) {
            if ( ! jTextField_proxyUsername.getText().isEmpty() && jPasswordField_proxyPassword.getPassword().length == 0) {
                // A proxy password is used. Build the appropriate user message.
                if (passwordMsg.isEmpty()) {
                    passwordMsg = "Enter a Proxy password.";
                    jTextField_proxyUsername.requestFocus();
                } else {
                    passwordMsg = "Enter a Lotus Notes and Proxy password.";
                }
            }
        }
        
        if ( ! passwordMsg.isEmpty() ) {
            statusBarSetWarning(passwordMsg);
        }
    }

    class SyncSwingWorker extends SwingWorker<Void, Void>
    {
        @Override
        protected Void doInBackground()
        {
            try {
                // Disable our timer while we are doing a sync
                syncTimer.stop();

                setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                doSync();
            }
            finally {
                setSyncScheduleDelay();
                setCursor(Cursor.getDefaultCursor());
            }
            
            return null;
        }
    }

    // These fields and class are used to setup an internal sync scheduler
    protected List<Integer> syncMinOffsetsList = new ArrayList<Integer>();
    protected javax.swing.Timer syncTimer;
    public SyncScheduleListener syncScheduleListener;
    
    // Define a listener that gets called every time our javax.swing.Timer "ticks"
    public class SyncScheduleListener implements ActionListener {
            @Override
            public void actionPerformed(ActionEvent evt) {
                // Do a sync
                new SyncSwingWorker().execute();
            }
        };

    // Based on the current time and our list of when the sync timer should "tick",
    // set the timer delay so it fires at the next appropriate time.
    protected void setSyncScheduleDelay() {
        syncTimer.stop();

        if (syncMinOffsetsList.size() == 0 || !jCheckBox_SyncAtMinOffsets.isSelected()) {
            jLabel_NextSyncTime.setVisible(false);
            return;
        }

        Calendar cal = Calendar.getInstance();
        // Get current number of minutes and fractional seconds past the hour
        int currMin = cal.get(Calendar.MINUTE);
        double currMins = currMin + (cal.get(Calendar.SECOND) / 60.0);

        int delayMsecs = -1;
        // Loop through all the sync offsets the user has specified
        for (Integer offsetMins : syncMinOffsetsList) {
            // If true, we've found the offset we should use
            if (offsetMins > currMins) {
                delayMsecs = (int)(((double)offsetMins - currMins) * 60000.0);
                cal.set(Calendar.MINUTE, offsetMins);
                break;
            }
        }

        if (delayMsecs == -1) {
            // There were no sync offsets > than the current time. This means
            // we have to set the delay into the next hour.
            //   Number of minutes until top of the hour = 60 - currMins
            //   syncMinOffsets.get(0) returns the first offset in our list
            delayMsecs = (int)((60.0 - currMins + (double)syncMinOffsetsList.get(0)) * 60000.0);
            cal.add(Calendar.HOUR, 1);
            cal.set(Calendar.MINUTE, syncMinOffsetsList.get(0));
        }
        
        // Make the delay at least 500 msecs
        if (delayMsecs < 500) delayMsecs = 500;

        // The timer has both an initial delay and a standard delay (which seems
        // unnecessary to me). Set them both to the same value.
        syncTimer.setInitialDelay(delayMsecs);
        syncTimer.setDelay(delayMsecs);

        syncTimer.start();

        DateFormat dfShort = DateFormat.getTimeInstance(DateFormat.SHORT);
        jLabel_NextSyncTime.setText("Next Scheduled Sync: " + dfShort.format(cal.getTime()));
    }


    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jButton_Cancel = new javax.swing.JButton();
        jTabbedPane_Main = new javax.swing.JTabbedPane();
        jPanel2 = new javax.swing.JPanel();
        jButton_Synchronize = new javax.swing.JButton();
        jScrollPane1 = new javax.swing.JScrollPane();
        jTextArea_Status = new javax.swing.JTextArea();
        jLabel_NextSyncTime = new javax.swing.JLabel();
        jPanel3 = new javax.swing.JPanel();
        jLabel11 = new javax.swing.JLabel();
        jCheckBox_SyncOnStart = new javax.swing.JCheckBox();
        jCheckBox_DiagnosticMode = new javax.swing.JCheckBox();
        jLabel17 = new javax.swing.JLabel();
        jCheckBox_SyncDescription = new javax.swing.JCheckBox();
        jCheckBox_SyncAlarms = new javax.swing.JCheckBox();
        jLabel20 = new javax.swing.JLabel();
        jTextField_DestinationCalendarName = new javax.swing.JTextField();
        jCheckBox_SyncMeetingAttendees = new javax.swing.JCheckBox();
        jLabel21 = new javax.swing.JLabel();
        jTextField_SyncDaysInPast = new javax.swing.JFormattedTextField();
        jTextField_SyncDaysInFuture = new javax.swing.JFormattedTextField();
        jLabel22 = new javax.swing.JLabel();
        jLabel23 = new javax.swing.JLabel();
        jLabel24 = new javax.swing.JLabel();
        jTextField_SyncMinOffsets = new javax.swing.JTextField();
        jCheckBox_SyncAtMinOffsets = new javax.swing.JCheckBox();
        jCheckBox_SyncAllSubjectsToValue = new javax.swing.JCheckBox();
        jCheckBox_SyncLocationAndRoom = new javax.swing.JCheckBox();
        jTextField_SyncAllSubjectsToThisValue = new javax.swing.JTextField();
        jPanel1 = new javax.swing.JPanel();
        jLabel3 = new javax.swing.JLabel();
        jTextField_GoogleUsername = new javax.swing.JTextField();
        jLabel5 = new javax.swing.JLabel();
        jLabel13 = new javax.swing.JLabel();
        jPasswordField_LotusNotesPassword = new javax.swing.JPasswordField();
        jLabel14 = new javax.swing.JLabel();
        jLabel15 = new javax.swing.JLabel();
        jTextField_LotusNotesMailFile = new javax.swing.JTextField();
        jTextField_LotusNotesServer = new javax.swing.JTextField();
        jLabel6 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        jCheckBox_enableProxy = new javax.swing.JCheckBox();
        jLabel8 = new javax.swing.JLabel();
        jTextField_proxyIP = new javax.swing.JTextField();
        jLabel7 = new javax.swing.JLabel();
        jTextField_proxyPort = new javax.swing.JTextField();
        jCheckBox_LotusNotesServerIsLocal = new javax.swing.JCheckBox();
        jLabel18 = new javax.swing.JLabel();
        jTextField_proxyUsername = new javax.swing.JTextField();
        jLabel19 = new javax.swing.JLabel();
        jPasswordField_proxyPassword = new javax.swing.JPasswordField();
        jButton_DetectLotusSettings = new javax.swing.JButton();
        jButton_DetectProxySettings = new javax.swing.JButton();
        jLabel25 = new javax.swing.JLabel();
        jTextField_LotusNotesServerDateFormat = new javax.swing.JTextField();
        jCheckBox_dontSaveSensitiveData = new javax.swing.JCheckBox();
        jLabel12 = new javax.swing.JLabel();
        jLabel_statusMessage = new javax.swing.JLabel();
        jButton_Help = new javax.swing.JButton();
        jButton_Minimize = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Lotus Notes to Google Calendar Synchronizer (LNGS)");
        setBackground(new java.awt.Color(254, 254, 254));
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosed(java.awt.event.WindowEvent evt) {
                formWindowClosed(evt);
            }
            public void windowIconified(java.awt.event.WindowEvent evt) {
                formWindowIconified(evt);
            }
            public void windowOpened(java.awt.event.WindowEvent evt) {
                formWindowOpened(evt);
            }
        });

        jButton_Cancel.setMnemonic('x');
        jButton_Cancel.setText("Exit");
        jButton_Cancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton_CancelActionPerformed(evt);
            }
        });

        jTabbedPane_Main.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                jTabbedPane_MainStateChanged(evt);
            }
        });

        jButton_Synchronize.setMnemonic('y');
        jButton_Synchronize.setText("Synchronize");
        jButton_Synchronize.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton_SynchronizeActionPerformed(evt);
            }
        });

        jTextArea_Status.setEditable(false);
        jTextArea_Status.setColumns(20);
        jTextArea_Status.setRows(5);
        jTextArea_Status.setText("Status messages display here.\n");
        jScrollPane1.setViewportView(jTextArea_Status);

        jLabel_NextSyncTime.setText("Next Scheduled Sync: 12:00 PM");

        org.jdesktop.layout.GroupLayout jPanel2Layout = new org.jdesktop.layout.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(jScrollPane1)
                    .add(jPanel2Layout.createSequentialGroup()
                        .add(230, 230, 230)
                        .add(jLabel_NextSyncTime, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 157, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                        .add(18, 18, 18)
                        .add(jButton_Synchronize)))
                .addContainerGap())
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel2Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jButton_Synchronize)
                    .add(jLabel_NextSyncTime))
                .add(18, 18, 18)
                .add(jScrollPane1, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 410, Short.MAX_VALUE)
                .addContainerGap())
        );

        jTabbedPane_Main.addTab("Perform Sync", jPanel2);

        jLabel11.setForeground(new java.awt.Color(51, 51, 255));
        jLabel11.setText("General Settings");

        jCheckBox_SyncOnStart.setText("Sync On Startup");
        jCheckBox_SyncOnStart.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncOnStart.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_SyncOnStart.setPreferredSize(new java.awt.Dimension(100, 23));

        jCheckBox_DiagnosticMode.setText("Diagnostic Mode");
        jCheckBox_DiagnosticMode.setToolTipText("When checked, additional info is shown during a sync and some .txt output files are created.");
        jCheckBox_DiagnosticMode.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_DiagnosticMode.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_DiagnosticMode.setPreferredSize(new java.awt.Dimension(100, 23));

        jLabel17.setForeground(new java.awt.Color(51, 51, 255));
        jLabel17.setText("Data To Sync");

        jCheckBox_SyncDescription.setText("Descriptions");
        jCheckBox_SyncDescription.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncDescription.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_SyncDescription.setPreferredSize(new java.awt.Dimension(100, 23));

        jCheckBox_SyncAlarms.setText("Alarms Become Google Reminders");
        jCheckBox_SyncAlarms.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncAlarms.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_SyncAlarms.setPreferredSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncAlarms.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBox_SyncAlarmsActionPerformed(evt);
            }
        });

        jLabel20.setText("Destination Calendar Name");

        jTextField_DestinationCalendarName.setToolTipText("The calendar name is case sensitive, i.e. \"my cal\" is different then \"My Cal\".");
        jTextField_DestinationCalendarName.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                jTextField_DestinationCalendarNameFocusLost(evt);
            }
        });

        jCheckBox_SyncMeetingAttendees.setText("Attendees are Listed at Top of Description");
        jCheckBox_SyncMeetingAttendees.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncMeetingAttendees.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_SyncMeetingAttendees.setPreferredSize(new java.awt.Dimension(100, 23));

        jLabel21.setForeground(new java.awt.Color(51, 51, 255));
        jLabel21.setText("Sync Date Range");

        jTextField_SyncDaysInPast.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("###0"))));
        jTextField_SyncDaysInPast.setText("7");
        jTextField_SyncDaysInPast.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                jTextField_SyncDaysInPastFocusLost(evt);
            }
        });

        jTextField_SyncDaysInFuture.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("###0"))));
        jTextField_SyncDaysInFuture.setText("60");
        jTextField_SyncDaysInFuture.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                jTextField_SyncDaysInFutureFocusLost(evt);
            }
        });

        jLabel22.setText("Days in the Past");

        jLabel23.setText("Days in the Future");

        jLabel24.setForeground(new java.awt.Color(51, 51, 255));
        jLabel24.setText("Sync Schedule");

        jTextField_SyncMinOffsets.setToolTipText("For example, an offset of 15 means syncs happen every hour at 1:15, 2:15, 3:15, etc.");
        jTextField_SyncMinOffsets.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                jTextField_SyncMinOffsetsFocusLost(evt);
            }
        });

        jCheckBox_SyncAtMinOffsets.setText("Sync at These Minute Offsets");
        jCheckBox_SyncAtMinOffsets.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncAtMinOffsets.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_SyncAtMinOffsets.setPreferredSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncAtMinOffsets.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                jCheckBox_SyncAtMinOffsetsItemStateChanged(evt);
            }
        });

        jCheckBox_SyncAllSubjectsToValue.setText("For Privacy, Set All Subjects to This Value");
        jCheckBox_SyncAllSubjectsToValue.setToolTipText("When checked, all subjects created in Google Calendar will be set to the specified value.");
        jCheckBox_SyncAllSubjectsToValue.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncAllSubjectsToValue.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_SyncAllSubjectsToValue.setPreferredSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncAllSubjectsToValue.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                jCheckBox_SyncAllSubjectsToValueItemStateChanged(evt);
            }
        });

        jCheckBox_SyncLocationAndRoom.setText("Location and Room");
        jCheckBox_SyncLocationAndRoom.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_SyncLocationAndRoom.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_SyncLocationAndRoom.setPreferredSize(new java.awt.Dimension(100, 23));

        jTextField_SyncAllSubjectsToThisValue.setToolTipText("");
        jTextField_SyncAllSubjectsToThisValue.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) {
                jTextField_SyncAllSubjectsToThisValueFocusLost(evt);
            }
        });

        org.jdesktop.layout.GroupLayout jPanel3Layout = new org.jdesktop.layout.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel3Layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(jPanel3Layout.createSequentialGroup()
                        .add(10, 10, 10)
                        .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING, false)
                            .add(jCheckBox_SyncDescription, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 140, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jCheckBox_SyncLocationAndRoom, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 199, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jCheckBox_SyncMeetingAttendees, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 338, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jPanel3Layout.createSequentialGroup()
                                .add(jLabel20)
                                .add(26, 26, 26)
                                .add(jTextField_DestinationCalendarName, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 330, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                            .add(jPanel3Layout.createSequentialGroup()
                                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.TRAILING, false)
                                    .add(jCheckBox_SyncAtMinOffsets, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .add(jCheckBox_SyncOnStart, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 179, Short.MAX_VALUE))
                                .add(9, 9, 9)
                                .add(jTextField_SyncMinOffsets, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 298, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                            .add(jPanel3Layout.createSequentialGroup()
                                .add(jCheckBox_SyncAllSubjectsToValue, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 253, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                .add(jTextField_SyncAllSubjectsToThisValue))
                            .add(jCheckBox_DiagnosticMode, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .add(jCheckBox_SyncAlarms, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 253, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                        .addContainerGap(20, Short.MAX_VALUE))
                    .add(jPanel3Layout.createSequentialGroup()
                        .add(jLabel17, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addContainerGap(org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .add(jPanel3Layout.createSequentialGroup()
                        .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                            .add(jLabel24, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 120, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jLabel21, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 120, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jLabel11, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 105, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jPanel3Layout.createSequentialGroup()
                                .add(10, 10, 10)
                                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING, false)
                                    .add(jLabel22, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 98, Short.MAX_VALUE)
                                    .add(jLabel23))
                                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                    .add(jTextField_SyncDaysInFuture, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 39, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                    .add(jTextField_SyncDaysInPast, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 39, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))))
                        .addContainerGap(org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel3Layout.createSequentialGroup()
                .addContainerGap()
                .add(jLabel11, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jTextField_DestinationCalendarName, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                    .add(jLabel20))
                .add(5, 5, 5)
                .add(jCheckBox_DiagnosticMode, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .add(18, 18, 18)
                .add(jLabel24, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jCheckBox_SyncOnStart, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jTextField_SyncMinOffsets)
                    .add(jCheckBox_SyncAtMinOffsets, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .add(9, 9, 9)
                .add(jLabel21, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .add(4, 4, 4)
                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel22)
                    .add(jTextField_SyncDaysInPast, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel23)
                    .add(jTextField_SyncDaysInFuture, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                .add(jLabel17, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jCheckBox_SyncLocationAndRoom, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jCheckBox_SyncDescription, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jCheckBox_SyncMeetingAttendees, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jCheckBox_SyncAlarms, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel3Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(jPanel3Layout.createSequentialGroup()
                        .add(3, 3, 3)
                        .add(jTextField_SyncAllSubjectsToThisValue, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                    .add(jCheckBox_SyncAllSubjectsToValue, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .add(78, 78, 78))
        );

        jTabbedPane_Main.addTab("Sync Settings", jPanel3);

        jPanel1.setAutoscrolls(true);

        jLabel3.setText("Email Address");

        jTextField_GoogleUsername.setText("user@google.com");
        jTextField_GoogleUsername.setPreferredSize(new java.awt.Dimension(6, 20));

        jLabel5.setForeground(new java.awt.Color(51, 51, 255));
        jLabel5.setText("Google Settings");

        jLabel13.setText("Password");

        jLabel14.setText("Server");

        jLabel15.setText("Mail File");

        jTextField_LotusNotesServer.setToolTipText("");

        jLabel6.setForeground(new java.awt.Color(51, 51, 255));
        jLabel6.setText("Lotus Notes Settings");

        jLabel9.setForeground(new java.awt.Color(51, 51, 255));
        jLabel9.setText("Security Settings");

        jCheckBox_enableProxy.setText("Use Proxy Server to Reach the Internet");
        jCheckBox_enableProxy.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                jCheckBox_enableProxyItemStateChanged(evt);
            }
        });

        jLabel8.setText("Server IP or Name");
        jLabel8.setPreferredSize(new java.awt.Dimension(97, 14));

        jTextField_proxyIP.setEnabled(false);

        jLabel7.setText("Port Number");
        jLabel7.setPreferredSize(new java.awt.Dimension(97, 14));

        jTextField_proxyPort.setEnabled(false);

        jCheckBox_LotusNotesServerIsLocal.setText("Local Server");
        jCheckBox_LotusNotesServerIsLocal.setMaximumSize(new java.awt.Dimension(100, 23));
        jCheckBox_LotusNotesServerIsLocal.setMinimumSize(new java.awt.Dimension(40, 23));
        jCheckBox_LotusNotesServerIsLocal.setPreferredSize(new java.awt.Dimension(100, 23));
        jCheckBox_LotusNotesServerIsLocal.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                jCheckBox_LotusNotesServerIsLocalItemStateChanged(evt);
            }
        });

        jLabel18.setText("Username (optional)");

        jTextField_proxyUsername.setEnabled(false);

        jLabel19.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        jLabel19.setText("Password (optional)");
        jLabel19.setPreferredSize(new java.awt.Dimension(97, 14));

        jPasswordField_proxyPassword.setEnabled(false);

        jButton_DetectLotusSettings.setMnemonic('d');
        jButton_DetectLotusSettings.setText("Detect Lotus Settings");
        jButton_DetectLotusSettings.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton_DetectLotusSettingsActionPerformed(evt);
            }
        });

        jButton_DetectProxySettings.setMnemonic('d');
        jButton_DetectProxySettings.setText("Detect Proxy Settings");
        jButton_DetectProxySettings.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton_DetectProxySettingsActionPerformed(evt);
            }
        });

        jLabel25.setText("Server Date Format");

        jTextField_LotusNotesServerDateFormat.setText("Detect");
        jTextField_LotusNotesServerDateFormat.setToolTipText("Generally you should leave this as \"Detect\". See Help for more information.");

        jCheckBox_dontSaveSensitiveData.setText("For Security, Don't Save Passwords on Exit");
        jCheckBox_dontSaveSensitiveData.setToolTipText("When checked, the Lotus Notes and Proxy passwords will not be saved to the config file and the user will have to enter the passwords each time LNGS is started.");
        jCheckBox_dontSaveSensitiveData.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                jCheckBox_dontSaveSensitiveDataItemStateChanged(evt);
            }
        });

        jLabel12.setForeground(new java.awt.Color(51, 51, 255));
        jLabel12.setText("Network Settings");

        org.jdesktop.layout.GroupLayout jPanel1Layout = new org.jdesktop.layout.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(jPanel1Layout.createSequentialGroup()
                        .add(jLabel5, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 237, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                        .addContainerGap(org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .add(jPanel1Layout.createSequentialGroup()
                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(jLabel6, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 178, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                .add(172, 172, 172)
                                .add(jButton_DetectLotusSettings))
                            .add(jLabel12, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 236, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(10, 10, 10)
                                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                    .add(jPanel1Layout.createSequentialGroup()
                                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                            .add(jLabel14, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 69, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                            .add(jLabel25, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 120, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                            .add(jLabel15, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 69, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                            .add(jLabel13, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 62, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                                        .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                            .add(jPanel1Layout.createSequentialGroup()
                                                .add(jTextField_LotusNotesServer, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 251, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                                .add(jCheckBox_LotusNotesServerIsLocal, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 1, Short.MAX_VALUE))
                                            .add(jPanel1Layout.createSequentialGroup()
                                                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.TRAILING, false)
                                                    .add(org.jdesktop.layout.GroupLayout.LEADING, jPasswordField_LotusNotesPassword)
                                                    .add(org.jdesktop.layout.GroupLayout.LEADING, jTextField_LotusNotesMailFile, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, 351, Short.MAX_VALUE)
                                                    .add(org.jdesktop.layout.GroupLayout.LEADING, jTextField_LotusNotesServerDateFormat, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 102, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                                                .add(0, 0, Short.MAX_VALUE))))
                                    .add(jPanel1Layout.createSequentialGroup()
                                        .add(21, 21, 21)
                                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                            .add(jPanel1Layout.createSequentialGroup()
                                                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                                    .add(jLabel7, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                                    .add(jLabel18, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 126, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                                    .add(jLabel19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 130, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                                                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                                    .add(jPanel1Layout.createSequentialGroup()
                                                        .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                                                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                                            .add(jTextField_proxyUsername, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 168, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                                            .add(jPasswordField_proxyPassword, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 168, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)))
                                                    .add(jPanel1Layout.createSequentialGroup()
                                                        .add(4, 4, 4)
                                                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                                            .add(jTextField_proxyIP, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 168, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                                                            .add(jTextField_proxyPort, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 63, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))))
                                                .add(0, 0, Short.MAX_VALUE))
                                            .add(jLabel8, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)))
                                    .add(jPanel1Layout.createSequentialGroup()
                                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                                            .add(jPanel1Layout.createSequentialGroup()
                                                .add(jLabel3)
                                                .add(58, 58, 58)
                                                .add(jTextField_GoogleUsername, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 351, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                                            .add(jPanel1Layout.createSequentialGroup()
                                                .add(jCheckBox_enableProxy)
                                                .add(121, 121, 121)
                                                .add(jButton_DetectProxySettings)))
                                        .add(0, 0, Short.MAX_VALUE)))))
                        .add(31, 31, 31))
                    .add(jPanel1Layout.createSequentialGroup()
                        .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                            .add(jLabel9, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 236, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                            .add(jPanel1Layout.createSequentialGroup()
                                .add(10, 10, 10)
                                .add(jCheckBox_dontSaveSensitiveData)))
                        .add(0, 0, Short.MAX_VALUE))))
        );

        jPanel1Layout.linkSize(new java.awt.Component[] {jLabel13, jLabel14, jLabel15}, org.jdesktop.layout.GroupLayout.HORIZONTAL);

        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel6, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                    .add(jButton_DetectLotusSettings))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel14)
                    .add(jTextField_LotusNotesServer, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                    .add(jCheckBox_LotusNotesServerIsLocal, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .add(7, 7, 7)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel25)
                    .add(jTextField_LotusNotesServerDateFormat, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jTextField_LotusNotesMailFile, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                    .add(jLabel15))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel13)
                    .add(jPasswordField_LotusNotesPassword, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .add(18, 18, 18)
                .add(jLabel5, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .add(1, 1, 1)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel3)
                    .add(jTextField_GoogleUsername, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .add(18, 18, 18)
                .add(jLabel12, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jButton_DetectProxySettings)
                    .add(jCheckBox_enableProxy))
                .add(1, 1, 1)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jTextField_proxyIP, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                    .add(jLabel8, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel7, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                    .add(jTextField_proxyPort))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel18)
                    .add(jTextField_proxyUsername))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.RELATED)
                .add(jPanel1Layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jLabel19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                    .add(jPasswordField_proxyPassword))
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                .add(jLabel9, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 19, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                .add(jCheckBox_dontSaveSensitiveData)
                .add(44, 44, 44))
        );

        jTabbedPane_Main.addTab("Connection Settings", jPanel1);

        jLabel_statusMessage.setBackground(new java.awt.Color(255, 148, 20));
        jLabel_statusMessage.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N
        jLabel_statusMessage.setText("This tool synchronizes a Lotus Notes calendar to a Google calendar.");
        jLabel_statusMessage.setMaximumSize(new java.awt.Dimension(324, 16));
        jLabel_statusMessage.setMinimumSize(new java.awt.Dimension(324, 16));

        jButton_Help.setMnemonic('H');
        jButton_Help.setText("Help");
        jButton_Help.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton_HelpActionPerformed(evt);
            }
        });

        jButton_Minimize.setMnemonic('m');
        jButton_Minimize.setText("Minimize");
        jButton_Minimize.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton_MinimizeActionPerformed(evt);
            }
        });

        org.jdesktop.layout.GroupLayout layout = new org.jdesktop.layout.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(layout.createSequentialGroup()
                .addContainerGap()
                .add(layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
                    .add(layout.createSequentialGroup()
                        .add(10, 10, 10)
                        .add(jButton_Cancel)
                        .add(171, 171, 171)
                        .add(jButton_Minimize)
                        .add(165, 165, 165)
                        .add(jButton_Help)
                        .add(0, 0, Short.MAX_VALUE))
                    .add(jTabbedPane_Main, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                    .add(jLabel_statusMessage, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, org.jdesktop.layout.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(org.jdesktop.layout.GroupLayout.LEADING)
            .add(org.jdesktop.layout.GroupLayout.TRAILING, layout.createSequentialGroup()
                .addContainerGap()
                .add(jLabel_statusMessage, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE, 16, org.jdesktop.layout.GroupLayout.PREFERRED_SIZE)
                .add(15, 15, 15)
                .add(jTabbedPane_Main)
                .addPreferredGap(org.jdesktop.layout.LayoutStyle.UNRELATED)
                .add(layout.createParallelGroup(org.jdesktop.layout.GroupLayout.BASELINE)
                    .add(jButton_Cancel)
                    .add(jButton_Minimize)
                    .add(jButton_Help))
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void jButton_CancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton_CancelActionPerformed
        formWindowClosed(null);
}//GEN-LAST:event_jButton_CancelActionPerformed

    private void jButton_SynchronizeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton_SynchronizeActionPerformed
        new SyncSwingWorker().execute();
}//GEN-LAST:event_jButton_SynchronizeActionPerformed

    private void formWindowOpened(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowOpened
        this.setTitle(this.getTitle() + " v" + appVersion);

        if ( ! configMgr.getApplicationVersion().equals(appVersion)) {
            showNewVersionMessage();
        }

        if (configMgr.getSyncOnStartup() && jButton_Synchronize.isEnabled()) {
            new SyncSwingWorker().execute();
        }
    }//GEN-LAST:event_formWindowOpened

    private void formWindowClosed(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosed
        try {
            if (saveSettingsOnExit)
                saveSettings();
        } catch (IOException ex) {
            statusAppendException("There was an error saving settings.", ex);
            // The next time the user clicks Exit, we will exit without saving.
            saveSettingsOnExit = false;
            return;
        }

        System.exit(ExitCodes.SUCCESS.ordinal());
    }//GEN-LAST:event_formWindowClosed

    private void jTextField_DestinationCalendarNameFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_jTextField_DestinationCalendarNameFocusLost
        // Trim whitespace from front and back of text
        jTextField_DestinationCalendarName.setText(jTextField_DestinationCalendarName.getText().trim());
    }//GEN-LAST:event_jTextField_DestinationCalendarNameFocusLost

    private void jButton_HelpActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton_HelpActionPerformed
        Exception caughtEx = null;
        try {
            // Get the absolute path to this app and append the help filename

            java.awt.Desktop.getDesktop().browse(new java.net.URI(helpFilename));
        } catch (URISyntaxException ex) {
            caughtEx = ex;
        } catch (IOException ex) {
            caughtEx = ex;
        } finally {
            if (caughtEx != null) {
                statusAppendException("There was a problem opening the help file.", caughtEx);
            }
        }
    }//GEN-LAST:event_jButton_HelpActionPerformed

    private void jTextField_SyncDaysInPastFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_jTextField_SyncDaysInPastFocusLost
        setDateRange();
    }//GEN-LAST:event_jTextField_SyncDaysInPastFocusLost

    private void jTextField_SyncDaysInFutureFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_jTextField_SyncDaysInFutureFocusLost
        setDateRange();
    }//GEN-LAST:event_jTextField_SyncDaysInFutureFocusLost

    private void jTextField_SyncMinOffsetsFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_jTextField_SyncMinOffsetsFocusLost
        validateSettings();
    }//GEN-LAST:event_jTextField_SyncMinOffsetsFocusLost

    private void jTextField_SyncAllSubjectsToThisValueFocusLost(java.awt.event.FocusEvent evt) {//GEN-FIRST:event_jTextField_SyncAllSubjectsToThisValueFocusLost
        
    }//GEN-LAST:event_jTextField_SyncAllSubjectsToThisValueFocusLost

    private void jCheckBox_SyncAtMinOffsetsItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_jCheckBox_SyncAtMinOffsetsItemStateChanged
        jTextField_SyncMinOffsets.setEnabled(jCheckBox_SyncAtMinOffsets.isSelected());
        jLabel_NextSyncTime.setVisible(jCheckBox_SyncAtMinOffsets.isSelected());
        validateSettings();

        if (!jCheckBox_SyncAtMinOffsets.isSelected())
            syncTimer.stop();
    }//GEN-LAST:event_jCheckBox_SyncAtMinOffsetsItemStateChanged

    private void jCheckBox_SyncAllSubjectsToValueItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_jCheckBox_SyncAllSubjectsToValueItemStateChanged
        jTextField_SyncAllSubjectsToThisValue.setEnabled(jCheckBox_SyncAllSubjectsToValue.isSelected());
    }//GEN-LAST:event_jCheckBox_SyncAllSubjectsToValueItemStateChanged

    private void jCheckBox_LotusNotesServerIsLocalItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_jCheckBox_LotusNotesServerIsLocalItemStateChanged
        jTextField_LotusNotesServer.setEnabled(!jCheckBox_LotusNotesServerIsLocal.isSelected());
    }//GEN-LAST:event_jCheckBox_LotusNotesServerIsLocalItemStateChanged

    private void formWindowIconified(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowIconified
        if (SystemTray.isSupported()) {
            // Hide the window in addition to minimizing it
            setVisible(false);
        }
        setExtendedState(ICONIFIED);
    }//GEN-LAST:event_formWindowIconified

    private void jButton_MinimizeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton_MinimizeActionPerformed
        formWindowIconified(null);
    }//GEN-LAST:event_jButton_MinimizeActionPerformed

    private void jButton_DetectLotusSettingsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton_DetectLotusSettingsActionPerformed
        Exception caughtEx = null;
        try {
            LotusNotesManager lotusNotesMgr = new LotusNotesManager();
            //lotusNotesMgr.setStatusMessageCallback(this);

            String mailFile = "";
            String dominoServer = "";
            boolean localServer = false;
            String pwd = new String(jPasswordField_LotusNotesPassword.getPassword());
            String msg = "Click OK to auto-detect the Lotus Notes mail file and server name. " +
                    "Any existing values will be overwritten.\n" +
                    "Note: If the detected server name doesn't work, the Help file describes how to manually find the server name.";
            int buttonChoice = javax.swing.JOptionPane.OK_OPTION;
            if (pwd.isEmpty()) {
                pwd = javax.swing.JOptionPane.showInputDialog(this, msg + "\n\nEnter your Lotus Notes password:", "Detect Settings", javax.swing.JOptionPane.PLAIN_MESSAGE);
            } else {
                buttonChoice = javax.swing.JOptionPane.showOptionDialog(this, msg, "Detect Settings",
                                   javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE,
                                   null, null, null);
            }
            
            if (pwd != null && buttonChoice == javax.swing.JOptionPane.OK_OPTION) {
                LotusNotesManager.LotusNotesSettings lns;
                lns = lotusNotesMgr.detectLotusSettings(pwd);
                
                jPasswordField_LotusNotesPassword.setText(pwd);
                jTextField_LotusNotesServer.setText(lns.getServerName());
                jCheckBox_LotusNotesServerIsLocal.setSelected(lns.hasLocalServer);
                // Call the state changed event because it doesn't always get fired by the above setSelected() call
                jCheckBox_LotusNotesServerIsLocalItemStateChanged(null);
                jTextField_LotusNotesMailFile.setText(lns.getMailFile());
            }
            
            statusClear();
        } catch (NotesException ex) {
            caughtEx = ex;
        } catch (LngsException ex) {
            caughtEx = ex;
        } finally {
            if (caughtEx != null) {
                statusClear();
                String msg = "There was a problem detecting Lotus Notes settings.";
                statusAppendException(msg, caughtEx);
                javax.swing.JOptionPane.showMessageDialog(this, msg + " Switch to the main tab to see the error details.", "Detect Settings Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }//GEN-LAST:event_jButton_DetectLotusSettingsActionPerformed

    private void jTabbedPane_MainStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_jTabbedPane_MainStateChanged
        // We changed tabs
        try {
            // Save settings
            saveSettings();
            
            // If we switched to the main/sync tab, then validate settings
            if (jTabbedPane_Main.getSelectedIndex() == TabIds.SYNC.ordinal()) {
                validateSettings();
            }
        } catch (IOException ex) {
            statusAppendException("There was an error saving settings.", ex);
        }        
    }//GEN-LAST:event_jTabbedPane_MainStateChanged

    private void jButton_DetectProxySettingsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton_DetectProxySettingsActionPerformed
	System.setProperty("java.net.useSystemProxies", "true");

    	List proxyList = null;
	try {
	    proxyList = ProxySelector.getDefault().select(new URI("http://www.gmail.com"));
	} 
	catch (URISyntaxException ex) {
            showException(ex);
	}
        
        final String MSG_TCPVIEW = "If you are running under Windows, search the LNGS Help for 'TCPView' for how to identify proxy settings.";
	if (proxyList != null) {
	    for (Iterator iter = proxyList.iterator(); iter.hasNext();) {
	    	java.net.Proxy proxy = (java.net.Proxy) iter.next();
	
	    	InetSocketAddress addr = (InetSocketAddress) proxy.address();
	
	    	if (addr == null) {
                    javax.swing.JOptionPane.showMessageDialog(null, "No proxy information could be detected. Make sure you are on the network where the proxy is active.\n" + MSG_TCPVIEW, "No Proxy Information", JOptionPane.INFORMATION_MESSAGE);
	    	} else {
                    jTextField_proxyIP.setText(addr.getHostName());
                    jTextField_proxyPort.setText(Integer.toString(addr.getPort()));
                    javax.swing.JOptionPane.showMessageDialog(null, "The proxy server and port fields have been updated with the detected values.\nThese values should work. If they don't work, you can search the LNGS Help file for suggestions.", "Proxy Information Updated", JOptionPane.INFORMATION_MESSAGE);
	    	}
	    }
	}
        else {
            javax.swing.JOptionPane.showMessageDialog(null, "There was a problem detecting proxy information.\nYou will have to manually enter the proxy info.\n" + MSG_TCPVIEW, "Error Detecting Proxy Information", JOptionPane.ERROR_MESSAGE);
        }        
    }//GEN-LAST:event_jButton_DetectProxySettingsActionPerformed

    private void jCheckBox_dontSaveSensitiveDataItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_jCheckBox_dontSaveSensitiveDataItemStateChanged
        promptForPasswords();
    }//GEN-LAST:event_jCheckBox_dontSaveSensitiveDataItemStateChanged

    private void jCheckBox_enableProxyItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_jCheckBox_enableProxyItemStateChanged
        jTextField_proxyIP.setEnabled(jCheckBox_enableProxy.isSelected());
        jTextField_proxyPort.setEnabled(jCheckBox_enableProxy.isSelected());
        jTextField_proxyUsername.setEnabled(jCheckBox_enableProxy.isSelected());
        jPasswordField_proxyPassword.setEnabled(jCheckBox_enableProxy.isSelected());
        validateSettings();
    }//GEN-LAST:event_jCheckBox_enableProxyItemStateChanged

    private void jCheckBox_SyncAlarmsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCheckBox_SyncAlarmsActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_jCheckBox_SyncAlarmsActionPerformed


    // Validate our configuration settings
    private void validateSettings() {
        try {
            jButton_Synchronize.setEnabled(true);

            if (jCheckBox_enableProxy.isSelected()) {
                if (jTextField_proxyIP.getText().isEmpty()) {
                    throw new LngsException("The Proxy Server IP/Name cannot be blank.");
                }
                if (jTextField_proxyPort.getText().isEmpty()) {
                   throw new LngsException("The Proxy Port cannot be blank.");
                }
            }

            if (jCheckBox_SyncAtMinOffsets.isSelected()) {
                validateSyncMinOffsets();
            }

            statusClear();
            statusAppendLine("Configuration settings were successfully validated.");
        } catch (LngsException ex) {
            statusClear();
            statusAppendLine("ERROR: A configuration setting is invalid.");
            statusAppendLine("Syncing is disabled until the problem is resolved:");
            statusAppendLine(ex.getMessage());

            jButton_Synchronize.setEnabled(false);
        }
    }

    // Validate the sync minute offsets setting.
    private void validateSyncMinOffsets() throws LngsException {
        // Parse and convert our list of sync scheduler offsets
        // The input string will be like this: "15, 30, 45"
        String[] syncOffsets = jTextField_SyncMinOffsets.getText().split(",");
        if (syncOffsets.length == 0 || (syncOffsets.length == 1 && syncOffsets[0].trim().isEmpty()))
            throw new LngsException("The Sync Min Offsets list is empty.  Specify at least one value.");

        syncMinOffsetsList.clear();
        for (String strOffset : syncOffsets) {
            try {
                strOffset = strOffset.trim();

                int intOffset = Integer.parseInt(strOffset);
                if (intOffset >= 0 && intOffset <= 59) {
                    syncMinOffsetsList.add(intOffset);
                }
                else {
                    throw new LngsException("In the Sync Min Offsets list, the offset value of '" + strOffset + "' is not between 0 and 59.");
                }
            } catch (NumberFormatException ex) {
                    throw new LngsException("In the Sync Min Offsets list, the offset value of '" + strOffset + "' is not a valid integer.");
            }
        }

        Collections.sort(syncMinOffsetsList);

        setSyncScheduleDelay();
    }


    @SuppressWarnings("static-access")
    private void saveSettings() throws IOException {
        if (configMgr == null) {
            return;
        }
        
        configMgr.setLotusNotesServer(jTextField_LotusNotesServer.getText());
        configMgr.setLotusNotesServerDateFormat(jTextField_LotusNotesServerDateFormat.getText());
        configMgr.setLotusNotesServerIsLocal(jCheckBox_LotusNotesServerIsLocal.isSelected());
        configMgr.setLotusNotesMailFile(jTextField_LotusNotesMailFile.getText());
        configMgr.setLotusNotesPassword(new String(jPasswordField_LotusNotesPassword.getPassword()));

        configMgr.setGoogleUserName(jTextField_GoogleUsername.getText());

        configMgr.setGoogleEnableProxy(jCheckBox_enableProxy.isSelected());
        configMgr.setGoogleProxyPort(jTextField_proxyPort.getText());
        configMgr.setGoogleProxyIP(jTextField_proxyIP.getText());
        configMgr.setGoogleProxyUsername(jTextField_proxyUsername.getText());
        configMgr.setGoogleProxyPassword(new String(jPasswordField_proxyPassword.getPassword()));
        configMgr.setGoogleCalendarName(jTextField_DestinationCalendarName.getText());

        configMgr.setSyncOnStartup(jCheckBox_SyncOnStart.isSelected());
        configMgr.setSyncAtMinOffsets(jCheckBox_SyncAtMinOffsets.isSelected());
        configMgr.setSyncMinOffsets(jTextField_SyncMinOffsets.getText());
        configMgr.setSyncAllSubjectsToValue(jCheckBox_SyncAllSubjectsToValue.isSelected());
        configMgr.setSyncAllSubjectsToThisValue(jTextField_SyncAllSubjectsToThisValue.getText());
        configMgr.setDiagnosticMode(jCheckBox_DiagnosticMode.isSelected());
        configMgr.setSyncDescription(jCheckBox_SyncDescription.isSelected());
        configMgr.setSyncLocationAndRoom(jCheckBox_SyncLocationAndRoom.isSelected());
        configMgr.setSyncAlarms(jCheckBox_SyncAlarms.isSelected());
        configMgr.setSyncDaysInFuture(Integer.parseInt(jTextField_SyncDaysInFuture.getText()));
        configMgr.setSyncDaysInPast(Integer.parseInt(jTextField_SyncDaysInPast.getText()));
        configMgr.setSyncMeetingAttendees(jCheckBox_SyncMeetingAttendees.isSelected());
        
        configMgr.setDontSaveSensitiveData(jCheckBox_dontSaveSensitiveData.isSelected());        

        //save configuration to file
        configMgr.writeConfig();
    }

    private void loadSettings() {
        jTextField_LotusNotesServer.setText(configMgr.getLotusNotesServer());
        jTextField_LotusNotesServerDateFormat.setText(configMgr.getLotusNotesServerDateFormat());
        jCheckBox_LotusNotesServerIsLocal.setSelected(configMgr.getLotusNotesServerIsLocal());
        // Call the state changed event because it doesn't always get fired by the above setSelected() call
        jCheckBox_LotusNotesServerIsLocalItemStateChanged(null);
        jTextField_LotusNotesMailFile.setText(configMgr.getLotusNotesMailFile());
        String s = configMgr.getLotusNotesPassword();
        jPasswordField_LotusNotesPassword.setText(configMgr.getLotusNotesPassword());

        jTextField_GoogleUsername.setText(configMgr.getGoogleUserName());
        jCheckBox_enableProxy.setSelected(configMgr.getGoogleEnableProxy());
        jTextField_proxyIP.setText(configMgr.getGoogleProxyIP());
        jTextField_proxyPort.setText(configMgr.getGoogleProxyPort());
        jTextField_proxyUsername.setText(configMgr.getGoogleProxyUsername());
        jPasswordField_proxyPassword.setText(configMgr.getGoogleProxyPassword());
        jTextField_DestinationCalendarName.setText(configMgr.getGoogleCalendarName());

        jCheckBox_SyncOnStart.setSelected(configMgr.getSyncOnStartup());
        jTextField_SyncMinOffsets.setText(configMgr.getSyncMinOffsets());
        jCheckBox_SyncAtMinOffsetsItemStateChanged(null);
        jCheckBox_SyncAtMinOffsets.setSelected(configMgr.getSyncAtMinOffsets());
        jCheckBox_SyncAllSubjectsToValue.setSelected(configMgr.getSyncAllSubjectsToValue());
        jCheckBox_SyncAllSubjectsToValueItemStateChanged(null);
        jTextField_SyncAllSubjectsToThisValue.setText(configMgr.getSyncAllSubjectsToThisValue());
        jCheckBox_DiagnosticMode.setSelected(configMgr.getDiagnosticMode());
        jCheckBox_SyncDescription.setSelected(configMgr.getSyncDescription());
        jCheckBox_SyncLocationAndRoom.setSelected(configMgr.getSyncLocationAndRoom());
        jCheckBox_SyncAlarms.setSelected(configMgr.getSyncAlarms());
        jTextField_SyncDaysInFuture.setText(Integer.toString(configMgr.getSyncDaysInFuture()));
        jTextField_SyncDaysInPast.setText(Integer.toString(configMgr.getSyncDaysInPast()));
        jCheckBox_SyncMeetingAttendees.setSelected(configMgr.getSyncMeetingAttendees());

        jCheckBox_dontSaveSensitiveData.setSelected(configMgr.getDontSaveSensitiveData());
        
        // Configure proxy settings
        proxyMgr.setProxyHost(configMgr.getGoogleProxyIP());
        proxyMgr.setProxyPort(configMgr.getGoogleProxyPort());
        proxyMgr.setEnabled(configMgr.getGoogleEnableProxy());
    }


    protected void setDateRange() {
        final String NEGATIVE_MSG = "must be a positive number. The negative sign has been removed from the value you entered.";
        final String NEGATIVE_TITLE = "Value Must Be Positive";
        
        // Define our min start date for entries we will process
        Calendar now = Calendar.getInstance();
        syncDaysInPast = 0;
        if (!jTextField_SyncDaysInPast.getText().isEmpty())
            syncDaysInPast = Integer.parseInt(jTextField_SyncDaysInPast.getText());
        if (syncDaysInPast < 0) {
            syncDaysInPast = Math.abs(syncDaysInPast);
            jTextField_SyncDaysInPast.setText(Integer.toString(syncDaysInPast));
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Sync Days In Past " + NEGATIVE_MSG,
                    NEGATIVE_TITLE, javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        now.add(Calendar.DATE, syncDaysInPast * -1);
        // Clear out the time portion
        now.set(Calendar.HOUR_OF_DAY, 0);
        now.set(Calendar.MINUTE, 0);
        now.set(Calendar.SECOND, 0);
        startDate = now.getTime();

        // Define our max end date for entries we will process
        now = Calendar.getInstance();
        syncDaysInFuture = 0;
        if (!jTextField_SyncDaysInFuture.getText().isEmpty())
            syncDaysInFuture = Integer.parseInt(jTextField_SyncDaysInFuture.getText());
        if (syncDaysInFuture < 0) {
            syncDaysInFuture = Math.abs(syncDaysInFuture);
            jTextField_SyncDaysInFuture.setText(Integer.toString(syncDaysInFuture));
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Sync Days In Future " + NEGATIVE_MSG,
                    NEGATIVE_TITLE, javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        now.add(Calendar.DATE, syncDaysInFuture);
        // Set the time portion
        now.set(Calendar.HOUR_OF_DAY, 23);
        now.set(Calendar.MINUTE, 59);
        now.set(Calendar.SECOND, 59);
        endDate = now.getTime();
    }

    /**
     * Display an exception in a message box.
     */
    protected void showException(Exception ex) {
        // Add the stack trace to the status area
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        javax.swing.JOptionPane.showMessageDialog(null, "An unexpected error occured. Here is the stack trace:\n\n" + sw.toString(), "An Error Occured", JOptionPane.ERROR_MESSAGE);
    }
    
    /**
     * Sets the status bar text.
     * Note: The status bar is a single line at the top of the GUI
     * which is different than the large, status text area in the GUI center.
     * GUI center... surround it with chocolate and you have a yummy treat.
     */
    protected void statusBarSet(String text) {
        if (!isSilentMode) {
            // Turn off opaque so the default system background is used
            jLabel_statusMessage.setOpaque(false);
            jLabel_statusMessage.setText(text);
        }
    }
            
    /**
     * Sets the status bar text with a warning background color.
     */
    protected void statusBarSetWarning(String text) {
        if (!isSilentMode) {
            // Turn on opaque so the warning background color is shown
            jLabel_statusMessage.setOpaque(true);
            jLabel_statusMessage.setText(text);
        }
    }
            
    /**
     * Clears the status text area.
     */
    protected void statusClear() {
        if (!isSilentMode) {
            jTextArea_Status.setText("");
        }
    }
            
    /**
     * Adds a line to the status area.
     * @param text - The text to add.
     */
    @Override
    public void statusAppendLine(String text) {
    	if (isSilentMode) {
    		System.out.println(text);
        } else {
    		jTextArea_Status.append(text + "\n");

            // Scroll to the bottom so the new text can be seen
            jTextArea_Status.setCaretPosition(jTextArea_Status.getDocument().getLength());
        }
    }

    /**
     * Adds a line to the status area in diagnostic format.
     * @param text - The text to add.
     */
    @Override
    public void statusAppendLineDiag(String text) {
        if (jCheckBox_DiagnosticMode.isSelected())
            statusAppendLine("   " + text);
    }

    /**
     * Adds text to the status area (without inserting a newline).
     * @param text - The text to add.
     */
    @Override
    public void statusAppend(String text) {
        if (isSilentMode)
        	System.out.print(text);
        else {
        	jTextArea_Status.append(text);

            // Scroll to the bottom so the new text can be seen
            jTextArea_Status.setCaretPosition(jTextArea_Status.getDocument().getLength());
        }
    }

    /**
     * Adds a line to the status area and starts a timer.
     * @param text - The text to add.
     */
    @Override
    public void statusAppendStart(String text) {
        statusStartTime = System.currentTimeMillis();

        if (jCheckBox_DiagnosticMode.isSelected()) {
            // In diag mode, the output will be like:
            //    Starting text
            //    Other diag output lines go here
            //    ...
            //    Starting text (elapsed time)
            // The final line will be written when statusAppendFinished() is called
            statusStartMsg = text;
            statusAppendLine(text);
        }
        else
            // In non-diag mode, the output will be like:
            //    Starting text (elapsed time)
            // The elapsed time will be written when statusAppendFinished() is called
            statusAppend(text);
    }

    /**
     * Writes the elapsed time (started with statusAppendStart()) to the status area.
     */
    @Override
    public void statusAppendFinished() {
        // Convert milliseonds to seconds and round to the tenths place
        long elapsedMillis = System.currentTimeMillis() - statusStartTime;
        BigDecimal elapsedSecs = new BigDecimal(elapsedMillis / 1000.0).setScale(1, BigDecimal.ROUND_HALF_UP);

        if (jCheckBox_DiagnosticMode.isSelected())
            statusAppendLine(statusStartMsg + " (done in " + elapsedSecs.toString() + " s)");
        else
            statusAppendLine(" (" + elapsedSecs.toString() + " s)");
    }

    /**
     * Adds a line to the status area followed by the stack trace of an exception.
     * @param text - The text to add.
     * @param ex - Exception to display the stack trace of.
     */
    @Override
    public void statusAppendException(String text, Exception ex) {
        statusAppendLine("\n\n=== ERROR ===");
        statusAppendLine(text);

        // Add the stack trace to the status area
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        statusAppendLine(sw.toString());
    }

    final String logFilename = "lngsync.log";
    final String logFullPath = "./" + logFilename;
    final String iconAppFullPath = "/images/lngs-icon.png";
    
    ImageIcon iconApp;
    TrayIcon trayIcon = null;

    LotusNotesManager lotusNotesMgr = new LotusNotesManager();
    GoogleManager googleMgr = new GoogleManager();
    ProxyManager proxyMgr;
    ConfigurationManager configMgr;
    private boolean isUrlValid = false;
    long statusStartTime = 0;
    String statusStartMsg;
    final String appVersion = "2.8";
    private boolean isSilentMode = false;
    private boolean saveSettingsOnExit = true;
    private String helpFilename = "(unknown)";

    // Our min and max dates for entries we will process.
    // If the calendar entry is outside this range, it is ignored.
    Date startDate = null;
    Date endDate = null;
    int syncDaysInPast = 0;
    int syncDaysInFuture = 0;

    enum TabIds { SYNC, SYNC_SETTINGS, CONNECTION_SETTINGS };
    
    // An exit code of 0 is success. All other values are failure.
    enum ExitCodes { SUCCESS, INVALID_PARM, EXCEPTION, MISSING_RESOURCE };
    static ExitCodes exitCode = ExitCodes.SUCCESS;

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton jButton_Cancel;
    private javax.swing.JButton jButton_DetectLotusSettings;
    private javax.swing.JButton jButton_DetectProxySettings;
    private javax.swing.JButton jButton_Help;
    private javax.swing.JButton jButton_Minimize;
    private javax.swing.JButton jButton_Synchronize;
    private javax.swing.JCheckBox jCheckBox_DiagnosticMode;
    private javax.swing.JCheckBox jCheckBox_LotusNotesServerIsLocal;
    private javax.swing.JCheckBox jCheckBox_SyncAlarms;
    private javax.swing.JCheckBox jCheckBox_SyncAllSubjectsToValue;
    private javax.swing.JCheckBox jCheckBox_SyncAtMinOffsets;
    private javax.swing.JCheckBox jCheckBox_SyncDescription;
    private javax.swing.JCheckBox jCheckBox_SyncLocationAndRoom;
    private javax.swing.JCheckBox jCheckBox_SyncMeetingAttendees;
    private javax.swing.JCheckBox jCheckBox_SyncOnStart;
    private javax.swing.JCheckBox jCheckBox_dontSaveSensitiveData;
    private javax.swing.JCheckBox jCheckBox_enableProxy;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel15;
    private javax.swing.JLabel jLabel17;
    private javax.swing.JLabel jLabel18;
    private javax.swing.JLabel jLabel19;
    private javax.swing.JLabel jLabel20;
    private javax.swing.JLabel jLabel21;
    private javax.swing.JLabel jLabel22;
    private javax.swing.JLabel jLabel23;
    private javax.swing.JLabel jLabel24;
    private javax.swing.JLabel jLabel25;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JLabel jLabel_NextSyncTime;
    private javax.swing.JLabel jLabel_statusMessage;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPasswordField jPasswordField_LotusNotesPassword;
    private javax.swing.JPasswordField jPasswordField_proxyPassword;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTabbedPane jTabbedPane_Main;
    private javax.swing.JTextArea jTextArea_Status;
    private javax.swing.JTextField jTextField_DestinationCalendarName;
    private javax.swing.JTextField jTextField_GoogleUsername;
    private javax.swing.JTextField jTextField_LotusNotesMailFile;
    private javax.swing.JTextField jTextField_LotusNotesServer;
    private javax.swing.JTextField jTextField_LotusNotesServerDateFormat;
    private javax.swing.JTextField jTextField_SyncAllSubjectsToThisValue;
    private javax.swing.JFormattedTextField jTextField_SyncDaysInFuture;
    private javax.swing.JFormattedTextField jTextField_SyncDaysInPast;
    private javax.swing.JTextField jTextField_SyncMinOffsets;
    private javax.swing.JTextField jTextField_proxyIP;
    private javax.swing.JTextField jTextField_proxyPort;
    private javax.swing.JTextField jTextField_proxyUsername;
    // End of variables declaration//GEN-END:variables
}
