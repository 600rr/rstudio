/*
 * TerminalList.java
 *
 * Copyright (C) 2009-17 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.workbench.views.terminal;

import java.util.Iterator;
import java.util.LinkedHashMap;

import org.rstudio.core.client.StringUtil;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.common.console.ConsoleProcess.ConsoleProcessFactory;
import org.rstudio.studio.client.common.console.ConsoleProcessInfo;
import org.rstudio.studio.client.workbench.prefs.model.UIPrefs;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalBusyEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalCwdEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSubprocEvent;

import com.google.inject.Inject;
import com.google.inject.Provider;

/**
 * List of terminals, with sufficient metadata to display a list of
 * available terminals and reconnect to them.
 */
public class TerminalList implements Iterable<String>,
                                     TerminalSubprocEvent.Handler,
                                     TerminalCwdEvent.Handler
{
   protected TerminalList() 
   {
      RStudioGinjector.INSTANCE.injectMembers(this); 
      eventBus_.addHandler(TerminalSubprocEvent.TYPE, this);
      eventBus_.addHandler(TerminalCwdEvent.TYPE, this);
   }

   @Inject
   private void initialize(Provider<ConsoleProcessFactory> pConsoleProcessFactory,
                           EventBus events,
                           UIPrefs uiPrefs)
   {
      pConsoleProcessFactory_ = pConsoleProcessFactory;
      eventBus_ = events;
      uiPrefs_ = uiPrefs;
   }

   /**
    * Add metadata from supplied TerminalSession
    * @param terminal terminal to add
    */
   public void addTerminal(TerminalSession term)
   {
      addTerminal(ConsoleProcessInfo.createTerminalMetadata(
            term.getHandle(),
            term.getCaption(),
            term.getTitle(),
            term.getSequence(),
            term.getHasChildProcs(),
            term.getCols(),
            term.getRows(),
            term.getShellType(),
            term.getAltBufferActive(),
            term.getCwd(),
            term.getAutoCloseMode(),
            term.getZombie(),
            term.getTrackEnv()));
   }

   /**
    * Add metadata from supplied ConsoleProcessInfo
    * @param procInfo metadata to add
    */
   public void addTerminal(ConsoleProcessInfo procInfo)
   {
      terminals_.put(procInfo.getHandle(), procInfo);
      updateTerminalBusyStatus();

   }

   /**
    * Change terminal title.
    * @param handle handle of terminal
    * @param title new title
    * @return true if title was changed, false if it was unchanged
    */
   public boolean retitleTerminal(String handle, String title)
   {
      ConsoleProcessInfo current = getMetadataForHandle(handle);
      if (current == null)
      {
         return false;
      }

      if (!current.getTitle().equals(title))
      {
         current.setTitle(title);
         return true;
      }
      return false;
   }

   /**
    * update has subprocesses flag
    * @param handle terminal handle
    * @param childProcs new subprocesses flag value
    * @return true if changed, false if unchanged
    */
   private boolean setChildProcs(String handle, boolean childProcs)
   {
      ConsoleProcessInfo current = getMetadataForHandle(handle);
      if (current == null)
      {
         return false;
      }

      if (current.getHasChildProcs() != childProcs)
      {
         current.setHasChildProcs(childProcs);
         return true;
      }
      return false;
   }

   /**
    * update current working directory
    * @param handle terminal handle
    * @param cwd new directory
    */
   private void setCwd(String handle, String cwd)
   {
      ConsoleProcessInfo current = getMetadataForHandle(handle);
      if (current == null)
         return;

      if (current.getCwd() != cwd)
      {
         current.setCwd(cwd);
      }
   }

   /**
    * Remove given terminal from the list
    * @param handle terminal handle
    */
   void removeTerminal(String handle)
   {
      terminals_.remove(handle);
      updateTerminalBusyStatus();
   }

   /**
    * Kill all known terminal server processes, remove them from the server-
    * side list, and from the client-side list.
    */
   void terminateAll()
   {
      for (final java.util.Map.Entry<String, ConsoleProcessInfo> item : terminals_.entrySet())
      {
         pConsoleProcessFactory_.get().interruptAndReap(item.getValue().getHandle());
      }
      terminals_.clear();
      updateTerminalBusyStatus();
   }

   /**
    * Number of terminals in cache.
    * @return number of terminals tracked by this object
    */
   public int terminalCount()
   {
      return terminals_.size();
   }

   /**
    * Return 0-based index of a terminal in the list.
    * @param handle terminal to find
    * @return 0-based index of terminal, -1 if not found
    */
   public int indexOfTerminal(String handle)
   {
      int i = 0;
      for (final java.util.Map.Entry<String, ConsoleProcessInfo> item : terminals_.entrySet())
      {
         if (item.getValue().getHandle().equals(handle))
         {
            return i;
         }
         i++;
      }

      return -1;
   }

   /**
    * Return terminal handle at given 0-based index
    * @param i zero-based index
    * @return handle of terminal at index, or null if invalid index
    */
   public String terminalHandleAtIndex(int i)
   {
      int j = 0;
      for (final java.util.Map.Entry<String, ConsoleProcessInfo> item : terminals_.entrySet())
      {
         if (i == j)
         {
            return item.getValue().getHandle();
         }
         j++;
      }
      return null;
   }

   /**
    * Determine if a caption is already in use
    * @param caption to check
    * @return true if caption is not in use (i.e. a new terminal can use it)
    */
   public boolean isCaptionAvailable(String caption)
   {
      for (final java.util.Map.Entry<String, ConsoleProcessInfo> item : terminals_.entrySet())
      {
         if (item.getValue().getCaption().equals(caption))
         {
            return false;
         }
      }

      return true;
   }

   /**
    * Obtain handle for given caption.
    * @param caption to find
    * @return handle if found, or null
    */
   public String handleForCaption(String caption)
   {
      for (final java.util.Map.Entry<String, ConsoleProcessInfo> item : terminals_.entrySet())
      {
         if (item.getValue().getCaption().equals(caption))
         {
            return item.getValue().getHandle();
         }
      }
      return null;
   }
   
   /**
    * Obtain autoclose mode for a given terminal handle.
    * @param handle handle to query
    * @return autoclose mode; if terminal not in list, returns AUTOCLOSE_DEFAULT
    */
   public int autoCloseForHandle(String handle)
   {
      ConsoleProcessInfo meta = getMetadataForHandle(handle);
      if (meta == null)
         return ConsoleProcessInfo.AUTOCLOSE_DEFAULT;
      else
         return meta.getAutoCloseMode();
   }

   /**
    * Get metadata for terminal with given handle.
    * @param handle handle of terminal of interest
    * @return terminal metadata or null if not found
    */
   private ConsoleProcessInfo getMetadataForHandle(String handle)
   {
      return terminals_.get(handle);
   }

   /**
    * Initiate startup of a new terminal
    */
   public void createNewTerminal()
   {
      ConsoleProcessInfo info = ConsoleProcessInfo.createNewTerminalInfo(
            nextTerminalSequence(), uiPrefs_.terminalTrackEnvironment().getValue());
      startTerminal(info);
   }

   /**
    * Initiate startup of a new terminal with specified caption.
    * @param caption desired caption; if null or empty creates standard caption
    * @return true if caption available, false if name already in use
    */
   public boolean createNamedTerminal(String caption)
   {
      if (StringUtil.isNullOrEmpty(caption))
      {
         createNewTerminal();
         return true;
      }
      
      // is this terminal name available?
      if (!isCaptionAvailable(caption))
      {
         return false;
      }
      
      ConsoleProcessInfo info = ConsoleProcessInfo.createNamedTerminalInfo(
            nextTerminalSequence(), caption, uiPrefs_.terminalTrackEnvironment().getValue());

      startTerminal(info);
      return true;
   }

   /**
    * Reconnect to a known terminal.
    * @param handle
    * @return true if terminal was known and reconnect initiated
    */
   public boolean reconnectTerminal(String handle)
   {
      ConsoleProcessInfo existing = getMetadataForHandle(handle);
      if (existing == null)
      {
         return false;
      }

      existing.setHandle(handle);
      startTerminal(existing);
      return true;
   }

   /**
    * @param handle handle to find
    * @return caption for that handle or null if no such handle
    */
   public String getCaption(String handle)
   {
      ConsoleProcessInfo data = getMetadataForHandle(handle);
      if (data == null)
      {
         return null;
      }
      return data.getCaption();
   }

   /**
    * @param handle handle to find
    * @return does terminal have subprocesses
    */
   public boolean getHasSubprocs(String handle)
   {
      ConsoleProcessInfo data = getMetadataForHandle(handle);
      if (data == null)
      {
         return true;
      }
      return data.getHasChildProcs();
   }

   /**
    * @return true if any of the terminal shells have subprocesses
    */
   public boolean haveSubprocs()
   {
      for (final ConsoleProcessInfo item : terminals_.values())
      {
         if (item.getHasChildProcs())
         {
            return true;
         }
      }
      return false;
   }

   /**
    * Choose a 1-based sequence number one higher than the highest currently 
    * known terminal number. We don't try to fill gaps if terminals are closed 
    * in the middle of the opened tabs.
    * @return Highest currently known terminal plus one
    */
   private int nextTerminalSequence()
   {
      int maxNum = ConsoleProcessInfo.SEQUENCE_NO_TERMINAL;
      for (final java.util.Map.Entry<String, ConsoleProcessInfo> item : terminals_.entrySet())
      {
         maxNum = Math.max(maxNum, item.getValue().getTerminalSequence());
      }
      return maxNum + 1;
   }

   private void startTerminal(ConsoleProcessInfo info)
   {
      TerminalSession newSession = new TerminalSession(
            info, uiPrefs_.blinkingCursor().getValue(), true /*focus*/);
      newSession.connect();
      updateTerminalBusyStatus();
   }

   private void updateTerminalBusyStatus()
   {
      eventBus_.fireEvent(new TerminalBusyEvent(haveSubprocs()));
   }

   @Override
   public Iterator<String> iterator()
   {
      return terminals_.keySet().iterator();
   }

   @Override
   public void onTerminalSubprocs(TerminalSubprocEvent event)
   {
      setChildProcs(event.getHandle(), event.hasSubprocs());
      updateTerminalBusyStatus();
   }

   @Override
   public void onTerminalCwd(TerminalCwdEvent event)
   {
      setCwd(event.getHandle(), event.getCwd());
   }

   /**
    * Map of terminal handles to terminal metadata; order they are added
    * is the order they will be iterated.
    */
   private LinkedHashMap<String, ConsoleProcessInfo> terminals_ = 
                new LinkedHashMap<String, ConsoleProcessInfo>();

   // Injected ----  
   private Provider<ConsoleProcessFactory> pConsoleProcessFactory_;
   private EventBus eventBus_;
   private UIPrefs uiPrefs_;
}