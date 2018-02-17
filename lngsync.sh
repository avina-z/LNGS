#!/bin/sh
# This script runs Lotus Notes to Google Calendar Synchronizer under Linux and OS X.

# NOTE: If you come up with enhancments to this script, please post
# them to the project's Open Discussion forum:
# https://sourceforge.net/projects/lngooglecalsync/forums  

# The application uses Notes.jar, the Java interface file installed with the
# Lotus Notes client. Notes.jar references some .so files under Linux and .dylib
# files under OS X.
# In particular, make sure the dir containing lsxbe.so, liblsxbe.so, or lsxbe.dylib
# is in the path.
# The default locations for Notes.jar and the library files are hard-coded below.

# The full path where java is located, e.g. "/myjava/java". Set to "" for auto-detect.
JAVA_PATH=""

OS_TYPE=`uname`

# Configure for Linux
if [ "$OS_TYPE" = "Linux" ]; then
  if [ -d "/opt/ibm/notes" ]; then          # Notes v9
     NOTES_PATH=/opt/ibm/notes
  elif [ -d "/opt/ibm/lotus/notes" ]; then  # Notes v8
     NOTES_PATH=/opt/ibm/lotus/notes
  else
     echo "The Linux Lotus Notes installation directory could NOT be determined. Exiting."
     exit 1
  fi
  export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:$NOTES_PATH
fi

# Configure for Apple OS X
JAVA_PATH_OSX=
if [ "$OS_TYPE" = "Darwin" ]; then
	if [ -d "/Applications/IBM Notes.app/Contents/MacOS" ]; then
		export NOTES_PATH=/Applications/IBM\ Notes.app/Contents/MacOS			
	elif [ -d "/Applications/Lotus Notes.app/Contents/MacOS" ]; then
		export NOTES_PATH=/Applications/Lotus\ Notes.app/Contents/MacOS
	elif [ -d "/Applications/Notes.app/Contents/MacOS" ]; then
		export NOTES_PATH=/Applications/Notes.app/Contents/MacOS
	else
		echo "The OS X Lotus Notes installation directory could NOT be determined. Exiting."
		exit 1
	fi

	# Use the OS X java_home utility to find a 32-bit version of Java
	JAVA_PATH_OSX=`/usr/libexec/java_home -d32`

	export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:"$NOTES_PATH"
	export DYLD_LIBRARY_PATH=$DYLD_LIBRARY_PATH:"$NOTES_PATH"
fi

SCRIPT_PATH=`dirname "$0"`
# Make invokable from any directory
cd "$SCRIPT_PATH"

# Make silent mode work for cronjobs by using an ugly X11 hack
if [ -z "$DISPLAY" ]; then
	# Try with default display
	export DISPLAY=:0.0
	
	# Make sure xset command exists
	if type "xset" > /dev/null 2>&1; then
	  xset -q 1> /dev/null 2>1
	  if [ $? != 0 ]; then
  		echo The DISPLAY environment variable was not set, and the attempt to manually set did not work.
	  	echo The application may not startup properly.
  	fi
	fi
fi

export PATH=$PATH:"$NOTES_PATH"
export MY_CLASSPATH="$NOTES_PATH/jvm/lib/ext/Notes.jar":./lngsync.jar:$(printf "%s\n"  ./lib/*.jar | tr "\n" ":")

if [ -z "$JAVA_PATH" ]; then
	# If the default Lotus Notes Java exists, use it
	if [ -e "$NOTES_PATH/jvm/bin/java" ]; then
		JAVA_PATH="$NOTES_PATH/jvm/bin/java"
	# If an OS X version of Java is present, use it
	elif [ -e "$JAVA_PATH_OSX/bin/java" ]; then
		JAVA_PATH="$JAVA_PATH_OSX/bin/java"
	# If JAVA_HOME points to a version of Java, use it
	elif [ -e "$JAVA_HOME/bin/java" ]; then
		JAVA_PATH="$JAVA_HOME/bin/java"
	# Let the OS find Java via the PATH
	else
		JAVA_PATH="java"
	fi
fi

JAVA_COMMAND="$JAVA_PATH "


if [ -n $1 ] && [ "$1" = "-silent" ]; then
  if [ "$OS_TYPE" = "Darwin" ]; then
    # This property will prevent the Java icon from showing in the Dock
    JAVA_OSX_OPTIONS=-Dapple.awt.UIElement=true
  fi
  
	echo Running Lotus Notes Google Calendar Sync in silent mode...
	LOG_FILE=$SCRIPT_PATH/lngsync.log
	$JAVA_COMMAND $JAVA_OSX_OPTIONS -cp "$MY_CLASSPATH" lngs.MainGUI $* > $LOG_FILE
	rc=$?
	echo Synchronization complete.  See log file $LOG_FILE
else 
	echo Running Lotus Notes Google Calendar Sync in GUI mode...
	$JAVA_COMMAND -cp "$MY_CLASSPATH" lngs.MainGUI $*
	rc=$? 
fi

exit $rc
