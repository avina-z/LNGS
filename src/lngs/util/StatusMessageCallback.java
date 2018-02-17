// This source code is released under the GPL v3 license, http://www.gnu.org/licenses/gpl.html.
// This file is part of the LNGS project: http://sourceforge.net/projects/lngooglecalsync.

package lngs.util;

/**
 * Defines the interface for displaying or logging status messages.
 */
public interface StatusMessageCallback {
    public void statusAppendLine(String text);
    public void statusAppend(String text);
    public void statusAppendLineDiag(String text);

    public void statusAppendStart(String text);
    public void statusAppendFinished();
    
    public void statusAppendException(String text, Exception ex);
}

