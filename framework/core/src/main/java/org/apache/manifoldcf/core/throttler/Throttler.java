/* $Id$ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.core.throttler;

import org.apache.manifoldcf.core.interfaces.*;
import java.util.*;

/** A Throttler object creates a virtual pool of connections to resources
* whose access needs to be throttled in number, rate of use, and byte rate.
* This code is modeled on the code for distributed connection pools, and is intended
* to work in a similar manner.  Basically, a periodic assessment is done about what the
* local throttling parameters should be (on a per-pool basis), and the local throttling
* activities then adjust what they are doing based on the new parameters.  A service
* model is used to keep track of which pools have what clients working with them.
* This implementation has the advantage that:
* (1) Only local throttling ever takes place on a method-by-method basis, which makes
*   it possible to use throttling even in streams and background threads;
* (2) Throttling resources are apportioned fairly, on average, between all the various
*   cluster members, so it is unlikely that any persistent starvation conditions can
*   arise.
*/
public class Throttler
{
  public static final String _rcsid = "@(#)$Id$";

  /** The service type prefix for throttle pools */
  protected final static String serviceTypePrefix = "_THROTTLEPOOL_";
  
  /** Throttle group hash table.  Keyed by throttle group type, value is throttling groups */
  protected final Map<String,ThrottlingGroups> throttleGroupsHash = new HashMap<String,ThrottlingGroups>();

  /** Create a throttler instance.  Usually there will be one of these per connector
  * type that needs throttling.
  */
  public Throttler()
  {
  }
  
  // There are a lot of synchronizers to coordinate here.  They are indeed hierarchical.  It is not possible to simply
  // throw a synchronizer at every level, and require that we hold all of them, because when we wait somewhere in the
  // inner level, we will continue to hold locks and block access to all the outer levels.
  //
  // Instead, I've opted for a model whereby individual resources are protected.  This is tricky to coordinate, though,
  // because (for instance) after a resource has been removed from the hash table, it had better be cleaned up
  // thoroughly before the outer lock is removed, or two versions of the resource might wind up coming into existence.
  // The general rule is therefore:
  // (1) Creation or deletion of resources involves locking the parent where the resource is being added or removed
  // (2) Anything that waits CANNOT also add or remove.
  
  /** Get all existing throttle groups for a throttle group type.
  * The throttle group type typically describes a connector class, while the throttle group represents
  * a namespace of bin names specific to that connector class.
  *@param throttleGroupType is the throttle group type.
  *@return the set of throttle groups for that group type.
  */
  public Set<String> getThrottleGroups(IThreadContext threadContext, String throttleGroupType)
    throws ManifoldCFException
  {
    synchronized (throttleGroupsHash)
    {
      return throttleGroupsHash.keySet();
    }
  }
  
  /** Remove a throttle group.
  *@param throttleGroupType is the throttle group type.
  *@param throttleGroup is the throttle group.
  */
  public void removeThrottleGroup(IThreadContext threadContext, String throttleGroupType, String throttleGroup)
    throws ManifoldCFException
  {
    // Removal.  Lock the whole hierarchy.
    synchronized (throttleGroupsHash)
    {
      ThrottlingGroups tg = throttleGroupsHash.get(throttleGroupType);
      if (tg != null)
      {
        tg.removeThrottleGroup(threadContext, throttleGroup);
      }
    }
  }
  
  /** Set or update throttle specification for a throttle group.  This creates the
  * throttle group if it does not yet exist.
  *@param throttleGroupType is the throttle group type.
  *@param throttleGroup is the throttle group.
  *@param throttleSpec is the desired throttle specification object.
  */
  public void createOrUpdateThrottleGroup(IThreadContext threadContext, String throttleGroupType, String throttleGroup, IThrottleSpec throttleSpec)
    throws ManifoldCFException
  {
    // Potential addition.  Lock the whole hierarchy.
    synchronized (throttleGroupsHash)
    {
      ThrottlingGroups tg = throttleGroupsHash.get(throttleGroupType);
      if (tg == null)
      {
        tg = new ThrottlingGroups(throttleGroupType);
        throttleGroupsHash.put(throttleGroupType, tg);
      }
      tg.createOrUpdateThrottleGroup(threadContext, throttleGroup, throttleSpec);
    }
  }

  /** Construct connection throttler for connections with specific bin names.  This object is meant to be embedded with a connection
  * pool of similar objects, and used to gate the creation of new connections in that pool.
  *@param throttleGroupType is the throttle group type.
  *@param throttleGroup is the throttle group.
  *@param binNames are the connection type bin names.
  *@return the connection throttling object, or null if the pool is being shut down.
  */
  public IConnectionThrottler obtainConnectionThrottler(String throttleGroupType, String throttleGroup, String[] binNames)
  {
    // No waiting, so lock the entire tree.
    synchronized (throttleGroupsHash)
    {
      ThrottlingGroups tg = throttleGroupsHash.get(throttleGroupType);
      if (tg != null)
        return tg.obtainConnectionThrottler(throttleGroup, binNames);
      return null;
    }
  }
  
  /** Poll periodically.
  */
  public void poll(IThreadContext threadContext, String throttleGroupType)
    throws ManifoldCFException
  {
    // No waiting, so lock the entire tree.
    synchronized (throttleGroupsHash)
    {
      ThrottlingGroups tg = throttleGroupsHash.get(throttleGroupType);
      if (tg != null)
        tg.poll(threadContext);
    }
      
  }
  
  /** Free unused resources.
  */
  public void freeUnusedResources(IThreadContext threadContext)
    throws ManifoldCFException
  {
    // This potentially affects the entire hierarchy.
    // Go through the whole pool and clean it out
    synchronized (throttleGroupsHash)
    {
      Iterator<ThrottlingGroups> iter = throttleGroupsHash.values().iterator();
      while (iter.hasNext())
      {
        ThrottlingGroups p = iter.next();
        p.freeUnusedResources(threadContext);
      }
    }
  }
  
  /** Shut down all throttlers and deregister them.
  */
  public void destroy(IThreadContext threadContext)
    throws ManifoldCFException
  {
    // This affects the entire hierarchy, so lock the whole thing.
    // Go through the whole pool and clean it out
    synchronized (throttleGroupsHash)
    {
      Iterator<ThrottlingGroups> iter = throttleGroupsHash.values().iterator();
      while (iter.hasNext())
      {
        ThrottlingGroups p = iter.next();
        p.destroy(threadContext);
        iter.remove();
      }
    }
  }

  // Protected methods and classes
  
  protected String buildServiceTypeName(String throttlingGroupType, String throttleGroupName)
  {
    return serviceTypePrefix + throttlingGroupType + "_" + throttleGroupName;
  }
  

  /** This class represents a throttling group pool */
  protected class ThrottlingGroups
  {
    /** The throttling group type for this throttling group pool */
    protected final String throttlingGroupTypeName;
    /** The pool of individual throttle group services for this pool, keyed by throttle group name */
    protected final Map<String,ThrottlingGroup> groups = new HashMap<String,ThrottlingGroup>();
    
    public ThrottlingGroups(String throttlingGroupTypeName)
    {
      this.throttlingGroupTypeName = throttlingGroupTypeName;
    }
    
    /** Update throttle specification */
    public void createOrUpdateThrottleGroup(IThreadContext threadContext, String throttleGroup, IThrottleSpec throttleSpec)
      throws ManifoldCFException
    {
      synchronized (groups)
      {
        ThrottlingGroup g = groups.get(throttleGroup);
        if (g == null)
        {
          g = new ThrottlingGroup(threadContext, throttlingGroupTypeName, throttleGroup, throttleSpec);
          groups.put(throttleGroup, g);
        }
        else
        {
          g.updateThrottleSpecification(throttleSpec);
        }
      }
    }
    
    /** Obtain connection throttler.
    *@return the throttler, or null of the hierarchy has changed.
    */
    public IConnectionThrottler obtainConnectionThrottler(String throttleGroup, String[] binNames)
    {
      synchronized (groups)
      {
        ThrottlingGroup g = groups.get(throttleGroup);
        if (g == null)
          return null;
        return g.obtainConnectionThrottler(binNames);
      }
    }
    
    /** Remove specified throttle group */
    public void removeThrottleGroup(IThreadContext threadContext, String throttleGroup)
      throws ManifoldCFException
    {
      // Must synch the whole thing, because otherwise there would be a risk of someone recreating the
      // group right after we removed it from the map, and before we destroyed it.
      synchronized (groups)
      {
        ThrottlingGroup g = groups.remove(throttleGroup);
        if (g != null)
        {
          g.destroy(threadContext);
        }
      }
    }
    
    /** Poll this set of throttle groups.
    */
    public void poll(IThreadContext threadContext)
      throws ManifoldCFException
    {
      synchronized (groups)
      {
        Iterator<String> iter = groups.keySet().iterator();
        while (iter.hasNext())
        {
          String throttleGroup = iter.next();
          ThrottlingGroup p = groups.get(throttleGroup);
          p.poll(threadContext);
        }
      }
    }

    /** Free unused resources */
    public void freeUnusedResources(IThreadContext threadContext)
      throws ManifoldCFException
    {
      synchronized (groups)
      {
        Iterator<ThrottlingGroup> iter = groups.values().iterator();
        while (iter.hasNext())
        {
          ThrottlingGroup g = iter.next();
          g.freeUnusedResources(threadContext);
        }
      }
    }
    
    /** Destroy and shutdown all */
    public void destroy(IThreadContext threadContext)
      throws ManifoldCFException
    {
      synchronized (groups)
      {
        Iterator<ThrottlingGroup> iter = groups.values().iterator();
        while (iter.hasNext())
        {
          ThrottlingGroup p = iter.next();
          p.destroy(threadContext);
          iter.remove();
        }
      }
    }
  }
  
  /** This class represents a throttling group, of a specific throttling group type.  It basically
  * describes an entire self-consistent throttling environment.
  */
  protected class ThrottlingGroup
  {
    /** Whether this pool is alive */
    protected boolean isAlive = true;
    /** Service type name */
    protected final String serviceTypeName;
    /** The (anonymous) service name */
    protected final String serviceName;
    /** The current throttle spec */
    protected IThrottleSpec throttleSpec;
    
    /** The connection bins */
    protected final Map<String,ConnectionBin> connectionBins = new HashMap<String,ConnectionBin>();
    /** The fetch bins */
    protected final Map<String,FetchBin> fetchBins = new HashMap<String,FetchBin>();
    /** The throttle bins */
    protected final Map<String,ThrottleBin> throttleBins = new HashMap<String,ThrottleBin>();

    // For synchronization, we use several in this class.
    // Modification to the connectionBins, fetchBins, or throttleBins hashes uses the appropriate local synchronizer.
    // Changes to other local variables use the main synchronizer.
    
    /** Constructor
    */
    public ThrottlingGroup(IThreadContext threadContext, String throttlingGroupType, String throttleGroup, IThrottleSpec throttleSpec)
      throws ManifoldCFException
    {
      this.serviceTypeName = buildServiceTypeName(throttlingGroupType, throttleGroup);
      this.throttleSpec = throttleSpec;
      // Now, register and activate service anonymously, and record the service name we get.
      ILockManager lockManager = LockManagerFactory.make(threadContext);
      this.serviceName = lockManager.registerServiceBeginServiceActivity(serviceTypeName, null, null);
      // Once all that is done, perform the initial setting of all the bin cutoffs
      poll(threadContext);
    }

    /** Update the throttle spec.
    *@param throttleSpec is the new throttle spec for this throttle group.
    */
    public synchronized void updateThrottleSpecification(IThrottleSpec throttleSpec)
      throws ManifoldCFException
    {
      this.throttleSpec = throttleSpec;
    }
    
    /** Obtain a connection throttler */
    public IConnectionThrottler obtainConnectionThrottler(String[] binNames)
    {
      return new ConnectionThrottler(this, binNames);
    }
    
    // IConnectionThrottler support methods
    
    /** Obtain connection permission.
    *@return null if we are marked as 'not alive'.
    */
    public IFetchThrottler obtainConnectionPermission(String[] binNames)
      throws InterruptedException
    {
      // First, make sure all the bins exist, and reserve a slot in each
      int i = 0;
      while (i < binNames.length)
      {
        String binName = binNames[i];
        ConnectionBin bin;
        synchronized (connectionBins)
        {
          bin = connectionBins.get(binName);
          if (bin == null)
          {
            bin = new ConnectionBin(binName);
            connectionBins.put(binName, bin);
          }
        }
        // Reserve a slot
        if (!bin.reserveAConnection())
        {
          // Release previous reservations, and return null
          while (i > 0)
          {
            i--;
            binName = binNames[i];
            synchronized (connectionBins)
            {
              bin = connectionBins.get(binName);
            }
            if (bin != null)
              bin.clearReservation();
          }
          return null;
        }
        i++;
      }
      
      // All reservations have been made!  Convert them.
      for (String binName : binNames)
      {
        ConnectionBin bin;
        synchronized (connectionBins)
        {
          bin = connectionBins.get(binName);
        }
        if (bin != null)
          bin.noteConnectionCreation();
      }
      return new FetchThrottler(this, binNames);
    }
    
    /** Count the number of bins that are over quota.
    *@return Integer.MAX_VALUE if shutting down.
    */
    public int overConnectionQuotaCount(String[] binNames)
    {
      // MHL
      return Integer.MAX_VALUE;
    }
    
    /** Release connection */
    public void releaseConnectionPermission(String[] binNames)
    {
      synchronized (connectionBins)
      {
        for (String binName : binNames)
        {
          ConnectionBin bin = connectionBins.get(binName);
          if (bin != null)
            bin.noteConnectionDestruction();
        }
      }
    }
    
    // IFetchThrottler support methods
    
    /** Get permission to fetch a document.  This grants permission to start
    * fetching a single document, within the connection that has already been
    * granted permission that created this object.  When done (or aborting), call
    * releaseFetchDocumentPermission() to note the completion of the document
    * fetch activity.
    *@param currentTime is the current time, in ms. since epoch.
    *@return the stream throttler to use to throttle the actual data access, or null if the system is being shut down.
    */
    public IStreamThrottler obtainFetchDocumentPermission(String[] binNames)
      throws InterruptedException
    {
      // First, make sure all the bins exist, and reserve a slot in each
      int i = 0;
      while (i < binNames.length)
      {
        String binName = binNames[i];
        FetchBin bin;
        synchronized (fetchBins)
        {
          bin = fetchBins.get(binName);
          if (bin == null)
          {
            bin = new FetchBin(binName);
            fetchBins.put(binName, bin);
          }
        }
        // Reserve a slot
        if (bin == null || !bin.reserveFetchRequest())
        {
          // Release previous reservations, and return null
          while (i > 0)
          {
            i--;
            binName = binNames[i];
            synchronized (fetchBins)
            {
              bin = fetchBins.get(binName);
            }
            if (bin != null)
              bin.clearReservation();
          }
          return null;
        }
        i++;
      }
      
      // All reservations have been made!  Convert them.
      // (These are guaranteed to succeed - but they may wait)
      i = 0;
      while (i < binNames.length)
      {
        String binName = binNames[i];
        FetchBin bin;
        synchronized (fetchBins)
        {
          bin = fetchBins.get(binName);
        }
        if (bin != null)
        {
          if (!bin.waitNextFetch())
          {
            // Undo the reservations we haven't processed yet
            while (i < binNames.length)
            {
              binName = binNames[i];
              synchronized (fetchBins)
              {
                bin = fetchBins.get(binName);
              }
              if (bin != null)
                bin.clearReservation();
              i++;
            }
            return null;
          }
        }
        i++;
      }
      
      // Do a "begin fetch" for all throttle bins
      synchronized (throttleBins)
      {
        for (String binName : binNames)
        {
          ThrottleBin bin = throttleBins.get(binName);
          if (bin == null)
          {
            bin = new ThrottleBin(binName);
            throttleBins.put(binName,bin);
          }
          bin.beginFetch();
        }
      }
      
      return new StreamThrottler(this, binNames);
    }
    
    // IStreamThrottler support methods
    
    /** Obtain permission to read a block of bytes.  This method may wait until it is OK to proceed.
    * The throttle group, bin names, etc are already known
    * to this specific interface object, so it is unnecessary to include them here.
    *@param byteCount is the number of bytes to get permissions to read.
    *@return true if the wait took place as planned, or false if the system is being shut down.
    */
    public boolean obtainReadPermission(String[] binNames, int byteCount)
      throws InterruptedException
    {
      int i = 0;
      while (i < binNames.length)
      {
        String binName = binNames[i];
        ThrottleBin bin;
        synchronized (throttleBins)
        {
          bin = throttleBins.get(binName);
        }
        if (bin == null || bin.beginRead(byteCount))
        {
          // End bins we've already done, and exit
          while (i > 0)
          {
            i--;
            binName = binNames[i];
            synchronized (throttleBins)
            {
              bin = throttleBins.get(binName);
            }
            if (bin != null)
              bin.endRead(byteCount,0);
          }
          return false;
        }
        i++;
      }
      return true;
    }
      
    /** Note the completion of the read of a block of bytes.  Call this after
    * obtainReadPermission() was successfully called, and bytes were successfully read.
    *@param origByteCount is the originally requested number of bytes to get permissions to read.
    *@param actualByteCount is the number of bytes actually read.
    */
    public void releaseReadPermission(String[] binNames, int origByteCount, int actualByteCount)
    {
      synchronized (throttleBins)
      {
        for (String binName : binNames)
        {
          ThrottleBin bin = throttleBins.get(binName);
          if (bin != null)
            bin.endRead(origByteCount, actualByteCount);
        }
      }
    }

    /** Note the stream being closed.
    */
    public void closeStream(String[] binNames)
    {
      synchronized (throttleBins)
      {
        for (String binName : binNames)
        {
          ThrottleBin bin = throttleBins.get(binName);
          if (bin != null)
            bin.endFetch();
        }
      }
    }

    // Bookkeeping methods
    
    /** Call this periodically.
    */
    public synchronized void poll(IThreadContext threadContext)
      throws ManifoldCFException
    {
      // This is where we reset all the bin targets using ILockManager.
      // But for now, to get things working, we just do the "stupid" thing,
      // and presume we're the only actor.
      // MHL
      synchronized (connectionBins)
      {
        for (ConnectionBin bin : connectionBins.values())
        {
          bin.updateMaxActiveConnections(throttleSpec.getMaxOpenConnections(bin.getBinName()));
        }
      }
  
      synchronized (fetchBins)
      {
        for (FetchBin bin : fetchBins.values())
        {
          bin.updateMinTimeBetweenFetches(throttleSpec.getMinimumMillisecondsPerFetch(bin.getBinName()));
        }
      }
      
      synchronized (throttleBins)
      {
        for (ThrottleBin bin : throttleBins.values())
        {
          bin.updateMinimumMillisecondsPerByte(throttleSpec.getMinimumMillisecondsPerByte(bin.getBinName()));
        }
      }
      
    }
    
    /** Free unused resources.
    */
    public synchronized void freeUnusedResources(IThreadContext threadContext)
      throws ManifoldCFException
    {
      // MHL
    }
    
    /** Destroy this pool.
    */
    public synchronized void destroy(IThreadContext threadContext)
      throws ManifoldCFException
    {
      synchronized (connectionBins)
      {
        Iterator<ConnectionBin> binIter = connectionBins.values().iterator();
        while (binIter.hasNext())
        {
          ConnectionBin bin = binIter.next();
          bin.shutDown();
          binIter.remove();
        }
      }
      
      synchronized (fetchBins)
      {
        Iterator<FetchBin> binIter = fetchBins.values().iterator();
        while (binIter.hasNext())
        {
          FetchBin bin = binIter.next();
          bin.shutDown();
          binIter.remove();
        }
      }
      
      synchronized (throttleBins)
      {
        Iterator<ThrottleBin> binIter = throttleBins.values().iterator();
        while (binIter.hasNext())
        {
          ThrottleBin bin = binIter.next();
          bin.shutDown();
          binIter.remove();
        }
      }

      // End service activity
      isAlive = false;
      notifyAll();
      ILockManager lockManager = LockManagerFactory.make(threadContext);
      lockManager.endServiceActivity(serviceTypeName, serviceName);
    }
  }
  
  /** Connection throttler implementation class.
  * This basically stores some parameters and links back to ThrottlingGroup.
  */
  protected static class ConnectionThrottler implements IConnectionThrottler
  {
    protected final ThrottlingGroup parent;
    protected final String[] binNames;
    
    public ConnectionThrottler(ThrottlingGroup parent, String[] binNames)
    {
      this.parent = parent;
      this.binNames = binNames;
    }
    
    /** Get permission to use a connection, which is described by the passed array of bin names.
    * This method may block until a connection slot is available.
    * The connection can be used multiple times until the releaseConnectionPermission() method is called.
    * This persistence feature is meant to allow connections to be pooled locally by the caller.
    *@return the fetch throttler to use when performing fetches from the corresponding connection, or null if the system is being shut down.
    */
    @Override
    public IFetchThrottler obtainConnectionPermission()
      throws InterruptedException
    {
      return parent.obtainConnectionPermission(binNames);
    }
    
    /** Determine whether to release a pooled connection.  This method returns the number of bins
    * where the outstanding connection exceeds current quotas, indicating whether at least one with the specified
    * characteristics should be released.
    * NOTE WELL: This method cannot judge which is the best connection to be released to meet
    * quotas.  The caller needs to do that based on the highest number of bins matched.
    *@return the number of bins that are over quota, or zero if none of them are.
    */
    @Override
    public int overConnectionQuotaCount()
    {
      return parent.overConnectionQuotaCount(binNames);
    }
    
    /** Release permission to use one connection. This presumes that obtainConnectionPermission()
    * was called earlier by someone and was successful.
    */
    @Override
    public void releaseConnectionPermission()
    {
      parent.releaseConnectionPermission(binNames);
    }

  }
  
  /** Fetch throttler implementation class.
  * This basically stores some parameters and links back to ThrottlingGroup.
  */
  protected static class FetchThrottler implements IFetchThrottler
  {
    protected final ThrottlingGroup parent;
    protected final String[] binNames;
    
    public FetchThrottler(ThrottlingGroup parent, String[] binNames)
    {
      this.parent = parent;
      this.binNames = binNames;
    }
    
    /** Get permission to fetch a document.  This grants permission to start
    * fetching a single document, within the connection that has already been
    * granted permission that created this object.  When done (or aborting), call
    * releaseFetchDocumentPermission() to note the completion of the document
    * fetch activity.
    *@return the stream throttler to use to throttle the actual data access, or null if the system is being shut down.
    */
    @Override
    public IStreamThrottler obtainFetchDocumentPermission()
      throws InterruptedException
    {
      return parent.obtainFetchDocumentPermission(binNames);
    }
    
  }
  
  /** Stream throttler implementation class.
  * This basically stores some parameters and links back to ThrottlingGroup.
  */
  protected static class StreamThrottler implements IStreamThrottler
  {
    protected final ThrottlingGroup parent;
    protected final String[] binNames;
    
    public StreamThrottler(ThrottlingGroup parent, String[] binNames)
    {
      this.parent = parent;
      this.binNames = binNames;
    }

    /** Obtain permission to read a block of bytes.  This method may wait until it is OK to proceed.
    * The throttle group, bin names, etc are already known
    * to this specific interface object, so it is unnecessary to include them here.
    *@param byteCount is the number of bytes to get permissions to read.
    *@return true if the wait took place as planned, or false if the system is being shut down.
    */
    @Override
    public boolean obtainReadPermission(int byteCount)
      throws InterruptedException
    {
      return parent.obtainReadPermission(binNames, byteCount);
    }
      
    /** Note the completion of the read of a block of bytes.  Call this after
    * obtainReadPermission() was successfully called, and bytes were successfully read.
    *@param origByteCount is the originally requested number of bytes to get permissions to read.
    *@param actualByteCount is the number of bytes actually read.
    */
    @Override
    public void releaseReadPermission(int origByteCount, int actualByteCount)
    {
      parent.releaseReadPermission(binNames, origByteCount, actualByteCount);
    }

    /** Note the stream being closed.
    */
    @Override
    public void closeStream()
    {
      parent.closeStream(binNames);
    }

  }
  
}
