' This script runs Lotus Notes to Google Calendar Synchronizer (LNGS) under Windows.
Option Explicit
dim lotusPath, lotusIniPath, useLotusJava, javaPath, lotusJavaPath, homeJavaPath

' NOTE: If this script fails to locate an important file, this can
'       probably be fixed by entering the path to the file below.
' Path where Notes.jar is located. Set to "" for auto-detect.
lotusPath = ""
' Path where Notes.ini is located. Set to "" for auto-detect.
lotusIniPath = ""
' Path where javaw.exe is located. Set to "" for auto-detect.
javaPath = ""
' Set to 1 to use the version of Java installed with Lotus.
' The Lotus version is guarenteed to work with Notes.jar.
' But we also need at least Java v1.6 to work with lngsync.jar.
' You will have to set this flag to 0 if you have Lotus v7 or earlier
' (because it comes with Java 1.4).
' Simple, eh?
useLotusJava = 1


dim appParm, silentMode, msgboxTitle
const FILE_READ = 1, FILE_WRITE = 2, FILE_APPEND = 8
silentMode = false
msgboxTitle = "Lotus Notes Google Sync"
dim oShell, oEnv, oFileSys, oJavawExec, oJavaExec
dim classPath, processPath, programFilesPath, lotusJarPath

set oShell = WScript.CreateObject("WScript.Shell")
set oFileSys = CreateObject("Scripting.FileSystemObject")

' There are environment variables for the System, User, and Process.
' The Process PATH should be the System and User PATHs combined.
set oEnv = oShell.Environment("Process")

' Read the Lotus Notes install path from the Registry. If the Registry
' read fails, try a default path.
On Error Resume Next
if lotusPath = "" then lotusPath = oShell.RegRead("HKEY_LOCAL_MACHINE\SOFTWARE\Lotus\Notes\Path")
if lotusPath = "" then lotusPath = oShell.RegRead("HKEY_LOCAL_MACHINE\SOFTWARE\Wow6432Node\Lotus\Notes\Path")
if lotusPath = "" then lotusPath = oShell.RegRead("HKEY_CURRENT_USER\Software\Lotus\Notes\Installer\PROGDIR")
if lotusPath = "" then lotusPath = oShell.RegRead("HKEY_CURRENT_USER\Software\Wow6432Node\Lotus\Notes\Installer\PROGDIR")
if lotusPath = "" then lotusPath = oShell.RegRead("HKEY_CURRENT_USER\Software\IBM\Notes\Installer\PROGDIR")
if lotusPath = "" then lotusPath = oShell.RegRead("HKEY_CURRENT_USER\Software\Wow6432Node\IBM\Notes\Installer\PROGDIR")
if lotusPath = "" then
	' Try to find the path where Lotus Notes is installed
	' First, get the Program Files path from the environment
	programFilesPath = oEnv.Item("ProgramFiles")
	lotusPath = programFilesPath & "\Lotus\Notes"
	if not oFileSys.FolderExists(lotusPath) then
		lotusPath = programFilesPath & "\IBM\Lotus\Notes"
		if not oFileSys.FolderExists(lotusPath) then
			' On 64-bit systems, Lotus is probably in the "Program Files (x86)" dir
			lotusPath = oEnv.Item("SystemDrive") & "\Program Files (x86)\Lotus\Notes" 
			if not oFileSys.FolderExists(lotusPath) then
				lotusPath = oEnv.Item("SystemDrive") & "\Program Files (x86)\IBM\Notes" 
				if not oFileSys.FolderExists(lotusPath) then
					lotusPath = oEnv.Item("SystemDrive") & "\Program Files (x86)\IBM\Lotus\Notes" 
				end if
			end if
		end if
	end if
end if

if lotusIniPath = "" then lotusIniPath = oShell.RegRead("HKEY_LOCAL_MACHINE\SOFTWARE\Lotus\Notes\DataPath")
if lotusIniPath = "" then lotusIniPath = oShell.RegRead("HKEY_LOCAL_MACHINE\SOFTWARE\IBM\Notes\DataPath")
if lotusIniPath = "" then lotusIniPath = oShell.RegRead("HKEY_LOCAL_MACHINE\SOFTWARE\Wow6432Node\Lotus\Notes\DataPath")
if lotusIniPath = "" then lotusIniPath = oShell.RegRead("HKEY_CURRENT_USER\Software\Lotus\Notes\Installer\DATADIR")
if lotusIniPath = "" then lotusIniPath = oShell.RegRead("HKEY_CURRENT_USER\Software\IBM\Notes\Installer\DATADIR")
if lotusIniPath = "" then lotusIniPath = oShell.RegRead("HKEY_CURRENT_USER\Software\Wow6432Node\Lotus\Notes\Installer\DATADIR")
if lotusIniPath = "" then lotusIniPath = lotusPath & "\Data"
' Cancel previous 'On Error' statement
On Error GoTo 0

' Try to find the Notes.jar file
lotusJarPath = lotusPath & "\jvm\lib\ext\Notes.jar"
if not oFileSys.FileExists(lotusJarPath) then
	lotusJarPath = lotusPath & "\Notes.jar"
  if not oFileSys.FileExists(lotusJarPath) then
	  MsgBox "The Notes.jar file could not be found. It is very unlikely the application will be able to run successfully.", _
	    vbExclamation, msgboxTitle
  end if
end if

if WScript.Arguments.Count > 0 then
	appParm = WScript.Arguments(0)
	if appParm = "-silent" then silentMode = true
end if

' Add the Lotus bin dir to the PATH because Notes.jar uses some Lotus Notes dlls.
' In particular, make sure the dir containing nlsxbe.dll is in the PATH.
processPath = oEnv.Item("PATH")
' Update the Process PATH because this is what 'javaw' will read
oEnv("PATH") = lotusPath & ";" & lotusIniPath & ";" & processPath  

' Set the classpath so Notes.jar can be found
classPath = lotusJarPath & ";" & oShell.CurrentDirectory & "\lngsync.jar"
oEnv("CLASSPATH") = classPath

' Get the path to the java executable
if not oFileSys.FileExists(javaPath) then
	if useLotusJava then
		lotusJavaPath = lotusPath & "\jvm\bin\javaw.exe"
		if oFileSys.FileExists(lotusJavaPath) then
			' Use the version of Java installed with Lotus Notes.
			' It is safest to use this version for compatibility with Notes.jar.
			javaPath = lotusJavaPath
		else
			useLotusJava = 2
		end if
	end if
	
	if not oFileSys.FileExists(javaPath) then
		homeJavaPath = oEnv.Item("JAVA_HOME") & "\bin\javaw.exe"
		if oFileSys.FileExists(homeJavaPath) then
			javaPath = homeJavaPath
		else
			' Let the OS find Java via the PATH
			javaPath = "javaw.exe"
		end if
	end if
end if

dim debugInfo, oLogFile, logFilename
logFilename = "lngsync.log"
debugInfo = "useLotusJava: " & useLotusJava & vbCRLF & vbCRLF & "lotusPath: " & lotusPath & vbCRLF & vbCRLF & "lotusJarPath: " & lotusJarPath & vbCRLF & vbCRLF & "lotusIniPath: " & lotusIniPath & vbCRLF & vbCRLF & "classPath: " & classPath & vbCRLF & vbCRLF & "javaPath: " & javaPath & vbCRLF & vbCRLF & "appParm: " & appParm
'MsgBox "DEBUG INFO" & vbCRLF & vbCRLF & debugInfo, vbOKOnly, "DEBUG" 
Set oLogFile = oFileSys.CreateTextFile(logFilename, TRUE)
oLogFile.WriteLine(debugInfo & vbCRLF)
oLogFile.Close


' Run the Java application
set oJavawExec = oShell.Exec("""" & javaPath & """ lngs.MainGUI " & appParm)
'set oJavawExec = oShell.Exec("""" & javaPath & """ -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStore=c:/Progra~2/Lotus/Notes/jvm/lib/security/cacerts lngs.MainGUI " & appParm)


' Wait for javaw to finish
Do While oJavawExec.Status = 0 
	WScript.Sleep 100 
Loop 

if silentMode then
	' Append stdout and stderr to our log file
	Set oLogFile = oFileSys.OpenTextFile(logFilename, FILE_APPEND, TRUE)
	oLogFile.WriteLine(oJavawExec.StdOut.ReadAll)
	oLogFile.WriteLine(oJavawExec.StdErr.ReadAll)
	oLogFile.Close
end if

if oJavawExec.ExitCode > 0 then
	if silentMode then
		MsgBox "There was an error running Lotus Notes Google Calender Sync in silent mode.  " & _
			"To get more information, run the application in GUI mode or see lngsync.log.", _
			vbExclamation, msgboxTitle
	else 
		set oJavaExec = oShell.Exec("""" & javaPath & """ -version")
		Do While oJavaExec.Status = 0 
			WScript.Sleep 100 
		Loop
		 
		MsgBox "The below error was encountered while starting Lotus Notes Google Calendar Sync.  " & _
			"If no error is shown, then an invalid command-line parameter was probably specified." & _
			vbCrLf & "Exit code: " & oJavawExec.ExitCode & _
			vbCrLf & oJavawExec.StdOut.ReadAll & _
			vbCrLf & oJavawExec.StdErr.ReadAll, _
			vbExclamation, msgboxTitle
			MsgBox "Below is the version of Java being used. Make sure the version is 1.6 or greater:" & vbCrLf & oJavaExec.StdErr.ReadAll, _
			vbExclamation, msgboxTitle
	end if
end if
