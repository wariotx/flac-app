/**
 * This file is part of Audioboo, an android program for audio blogging.
 * Copyright (C) 2011 Audioboo Ltd.
 * Copyright (C) 2010,2011 Audioboo Ltd.
 * All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

package fm.audioboo.application;

import android.content.Context;
import android.content.Intent;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;

import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;

import android.telephony.TelephonyManager;
import java.math.BigInteger;
import java.security.MessageDigest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.StringTokenizer;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Random;
import java.util.Locale;

import android.content.SharedPreferences;

import android.location.LocationManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationProvider;

import android.content.DialogInterface;
import android.app.Dialog;
import android.app.AlertDialog;

import android.content.res.Resources;

import android.provider.Settings;

import fm.audioboo.service.BooPlayerClient;
import fm.audioboo.service.UploadClient;
import fm.audioboo.service.Constants;
import fm.audioboo.service.PollReceiver;
import fm.audioboo.data.User;

import java.lang.ref.WeakReference;

import android.util.Log;

/**
 * Globals; uses singleton pattern to ensure that all members exist only
 * once. Created and destroyed when the app is started/stopped.
 **/
public class Globals
       implements BooPlayerClient.BindListener,
                  UploadClient.BindListener
{
  /***************************************************************************
   * Private constants
   **/
  private static final String     LTAG = "Globals";

  // Maxim number of items we want in the image cache.
  private static final int        IMAGE_CACHE_MAX = 200;

  // Defaults for the client ID.
  private static final String     CLIENT_ID_PREFIX  = "android-";
  private static final String     CLIENT_ID_UNKNOWN = "unknown-id";
  private static final String     HARDWARE_UNKNOWN  = "unknown-hardware";

  // Time interval to expect location updates in, in milliseconds. This is only
  // a guideline, and location updates may occur more or less often than that.
  // The 30 second interval is more or less picked at random; there's the
  // assumption that people won't move fast enough for a higher update frequency
  // to become important.
  private static final int        FREQUENT_LOCATION_UPDATE_PERIOD   = 15 * 1000;
  private static final int        INFREQUENT_LOCATION_UPDATE_PERIOD = 10 * 60 * 1000;

  // Thresholds for location update frequency changes, in meters. If the accuracy
  // goes below the maximum threshold we switch to updating infrequently. Yes,
  // that's a bit confusing, but due to the unit of measurement it's correct. If
  // the accuarcy goes above the minimum threshold, we switch to updating
  // frequently.
  private static final float      MAX_LOCATION_ACCURACY             = 30.0f;
  private static final float      MIN_LOCATION_ACCURACY             = 75.0f;

  // Resolution for location updates, in meters. Again the number is picked
  // based on the assumption that a higher resolution is not useful. What this
  // value means in practice is that we won't receive location updates if the
  // location changed by less than this amount.
  // XXX To make sense in conjunction with the MAX_LOCATION_ACCURACY above, this
  //     value must be smaller than MAX_LOCATION_ACCURACY.
  private static final float      LOCATION_UPDATE_RESOLUTION  = 20;

  // Directory prefix for the data directory.
  private static final String     DATA_DIR_PREFIX             = "boo_data";

  // Possible poll intervals
  private static final long[] POLL_INTERVALS = {
    AlarmManager.INTERVAL_FIFTEEN_MINUTES,
    AlarmManager.INTERVAL_HALF_HOUR,
    AlarmManager.INTERVAL_HOUR,
    AlarmManager.INTERVAL_HALF_DAY,
    AlarmManager.INTERVAL_DAY,
  };


  /***************************************************************************
   * Public constants
   **/
  // Preferences
  public static final String      PREFERENCES_NAME    = "fm.audioboo.application";

  public static final String      PREF_API_KEY        = "api.key";
  public static final String      PREF_API_SECRET     = "api.secret";

  public static final String      PREF_USE_LOCATION   = "settings.use_location";

  public static final String      PREF_RECORDING_TIME = "settings.cached-recording-time";

  // Reusable dialog IDs. The ones defined here start from 10000.
  public static final int         DIALOG_GPS_SETTINGS = 10000;
  public static final int         DIALOG_ERROR        = 10001;

  // Constant here as it's re-used elsewhere
  public static final int         THUMB_IMAGE_WIDTH   = 58;
  public static final int         THUMB_IMAGE_HEIGHT  = 58;
  public static final int         FULL_IMAGE_WIDTH    = 300;
  public static final int         FULL_IMAGE_HEIGHT   = 200;


  /***************************************************************************
   * Listener interface
   **/
  public static interface ClientBindListener
  {
    public void onBound();
  }


  /***************************************************************************
   * Singleton data
   **/
  private static Globals  sInstance;


  /***************************************************************************
   * Private instance data
   **/
  // Generated at startup; the algorithm always produces the same string for the
  // same device.
  private String                    mClientID;

  // Current location provider. Used to determine e.g. if the user needs to be
  // asked to enable location providers.
  private String                    mLocationProvider;

  // Location listener. Used to continuously update location information.
  private LocationListener          mLocationListener;

  // Current location update frequency
  private int                       mLocationUpdatePeriod;

  // Boo manager instance.
  private BooManager                mBooManager;

  // Listener for client bound changes.
  private ClientBindListener        mClientBindListener;

  // Map of error codes to error messages.
  private HashMap<Integer, String>  mErrorMessages;

  // Poll intent.
  private PendingIntent             mPollIntent;

  // Cache/update linked status of the app
  private API.Status                mStatus;
  private volatile int              mStatusRetries = 0;
  private volatile int              mStatusAuthErrors = 0;
  private Handler                   mOnwardsHandler;
  private Handler                   mStatusHandler = new Handler(new Handler.Callback() {
      public boolean handleMessage(Message msg)
      {
        if (API.ERR_SUCCESS == msg.what) {
          mStatus = mAPI.getStatus();
          Log.i(LTAG, "Device link status: " + mStatus);
          if (mStatus.mLinked) {
            mAPI.fetchAccount(mAccountHandler);
          }
          else {
            if (null != mOnwardsHandler) {
              mOnwardsHandler.obtainMessage(msg.what).sendToTarget();
              mOnwardsHandler = null;
            }
          }

          // Update recording time prefrence.
          SharedPreferences prefs = getPrefs();
          if (null != prefs) {
            SharedPreferences.Editor edit = prefs.edit();
            edit.putInt(PREF_RECORDING_TIME, mStatus.mRecordDuration);
            edit.commit();
          }

        }
        else {
          ++mStatusRetries;
          if (API.ERR_AUTHENTICATION == msg.what) {
            ++mStatusAuthErrors;
          }

          if (API.STATUS_UPDATE_MAX_RETRIES <= mStatusRetries) {
            Log.e(LTAG, "Giving up after " + mStatusRetries + " attempts.");

            if (API.STATUS_UPDATE_MAX_RETRIES <= mStatusAuthErrors) {
              // It's probably not a good idea to keep these credentials now
              clearCredentials();
            }

            if (null != mOnwardsHandler) {
              mOnwardsHandler.obtainMessage(msg.what).sendToTarget();
              mOnwardsHandler = null;
            }
          }
          else {
            // Try again.
            mAPI.updateStatus(mStatusHandler);
          }
        }
        return true;
      }
  });

  private Handler                   mAccountHandler = new Handler(new Handler.Callback() {
      public boolean handleMessage(Message msg)
      {
        if (API.ERR_SUCCESS == msg.what) {
          mAccount = (User) msg.obj;

          String key = String.format(Locale.US, ContactDetailsActivity.CONTACT_KEY_FORMAT, mAccount.mId);
          mObjectCache.put(key, mAccount, ContactDetailsActivity.CONTACT_TIMEOUT);
        }
        if (null != mOnwardsHandler) {
          mOnwardsHandler.obtainMessage(msg.what).sendToTarget();
          mOnwardsHandler = null;
        }
        return true;
      }
  });



  /***************************************************************************
   * Public instance data
   **/
  public WeakReference<Context> mContext;
  public API                    mAPI;
  public ImageCache             mImageCache;
  public BooPlayerClient        mPlayer;
  public UploadClient           mUploader;
  public TitleGenerator         mTitleGenerator;
  public ObjectMemoryCache      mObjectCache;

  // Location information, updated regularly if the appropriate settings are
  // switched on.
  public Location               mLocation;

  // Account information; only set if the device is linked.
  public User                   mAccount;



  /***************************************************************************
   * Singleton implementation
   **/
  public static void create(Context context)
  {
    if (null == sInstance) {
      sInstance = new Globals(context);
    }
  }



  public static void destroy(Context context)
  {
    if (null == sInstance) {
      return;
    }
    if (context != sInstance.mContext.get()) {
      return;
    }

    sInstance.release();
    sInstance = null;
  }



  public static Globals get()
  {
    return sInstance;
  }



  /***************************************************************************
   * BindListener implementation
   **/
  public void onBound(BooPlayerClient client)
  {
    mPlayer = client;
    if (null != mClientBindListener) {
      mClientBindListener.onBound();
    }
  }



  public void onBound(UploadClient client)
  {
    mUploader = client;
  }



  /***************************************************************************
   * Implementation
   **/
  private Globals(Context context)
  {
    mContext = new WeakReference<Context>(context);

    mAPI = new API();
    mImageCache = new ImageCache(context, IMAGE_CACHE_MAX);

    boolean bindResult = BooPlayerClient.bindService(context, this);
    bindResult = UploadClient.bindService(context, this);

    mTitleGenerator = new TitleGenerator(context);

    mObjectCache = new ObjectMemoryCache();

    // Find out the device linked status early.
    updateStatus(null);

    // Start polling, if necessary
    updatePolling();
  }



  public void setClientBindListener(ClientBindListener listener)
  {
    mClientBindListener = listener;
  }



  private void release()
  {
    mAPI = null;
    mImageCache = null;

    mTitleGenerator = null;

    mObjectCache = null;
  }



  /**
   * Generates (and returns) mClientID
   **/
  public String getClientID()
  {
    if (null != mClientID) {
      return mClientID;
    }

    Context ctx = mContext.get();
    if (null == ctx) {
      return null;
    }

    // Try to read client ID first.
    File d = ctx.getDir("metadata", Context.MODE_PRIVATE);
    String filename = d.getPath() + File.separator + "id";
    try {
      File f = new File(filename);
      FileInputStream is = new FileInputStream(f);
      byte[] buf = new byte[1024];
      int size = is.read(buf);
      is.close();

      String id = new String(buf, 0, size);
      mClientID = id;

      // Log.d(LTAG, "Stored ID >" + id + "<");
      return mClientID;
    } catch (IOException ex) {
      Log.e(LTAG, "Could not read device ID; generating new one.");
    }

    // Generate new ID.
    TelephonyManager tman = (TelephonyManager) ctx.getSystemService(
        Context.TELEPHONY_SERVICE);
    String deviceId = tman.getDeviceId();

    try {
      // Let's be nice to our users and not send out their device ID in plain
      // text.
      MessageDigest m = MessageDigest.getInstance("SHA-1");
      m.update((CLIENT_ID_PREFIX + getHardwareString() + deviceId).getBytes());

      // Also add some random bytes.
      Random r = new Random(System.currentTimeMillis());
      byte[] buf = new byte[128];
      r.nextBytes(buf);
      m.update(buf);

      // Generate digest.
      String digest = new BigInteger(1, m.digest()).toString(16);
      while (digest.length() < 32) {
        digest = "0" + digest;
      }
      mClientID = CLIENT_ID_PREFIX + digest;
      // Log.d(LTAG, "Generated ID >" + mClientID + "<");

      // At this point we have a good client ID that we can store.
      try {
        File f = new File(filename);
        FileOutputStream os = new FileOutputStream(f);
        os.write(mClientID.getBytes());
        os.close();
      } catch (IOException ex) {
        Log.e(LTAG, "Could not write device ID; we'll generate a new one next time.");
      }

    } catch (java.security.NoSuchAlgorithmException ex) {
      Log.e(LTAG, "ERROR: could not determine client ID: " + ex.getMessage());
      // We'll have to set the client ID to something to avoid errors further
      // down the line.
      mClientID = CLIENT_ID_PREFIX + CLIENT_ID_UNKNOWN;
    }

    return mClientID;
  }



  /**
   * Constructs a hardware identifier string from /proc/cpuinfo and returns it.
   **/
  public String getHardwareString()
  {
    // This should uniquely identify the hardware, without including information
    // about the system software. The fields DISPLAY, FINGERPRINT, etc.
    // all include information about the SDK.
    return String.format(Locale.US, "%s:%s:%s:%s",
        Build.BOARD, Build.BRAND, Build.DEVICE, Build.MODEL);
  }



  /**
   * Returns the App's preferences
   **/
  public SharedPreferences getPrefs()
  {
    Context ctx = mContext.get();
    if (null == ctx) {
      return null;
    }

    return ctx.getSharedPreferences(PREFERENCES_NAME,
        Context.MODE_PRIVATE);
  }



  /**
   * Return a pair of API key/secret, or null if that does not exist.
   **/
  public Pair<String, String> getCredentials()
  {
    SharedPreferences prefs = getPrefs();
    if (null == prefs) {
      return null;
    }

    String apiKey = prefs.getString(Globals.PREF_API_KEY, null);
    String apiSecret = prefs.getString(Globals.PREF_API_SECRET, null);
    if (null == apiKey || null == apiSecret) {
      return null;
    }

    return new Pair<String, String>(apiKey, apiSecret);
  }



  /**
   * Clears stored credentials.
   **/
  public void clearCredentials()
  {
    Log.e(LTAG, "Clearing credentials.");

    // Automatically means our status needs to be cleared
    mAPI.resetCredentials();
    mStatus = null;

    SharedPreferences prefs = getPrefs();
    if (null == prefs) {
      return;
    }

    SharedPreferences.Editor edit = prefs.edit();
    edit.remove(Globals.PREF_API_KEY);
    edit.remove(Globals.PREF_API_SECRET);
    edit.remove(Globals.PREF_RECORDING_TIME);
    edit.commit();
  }



  /**
   * Starts listening for updates to the device's location, if that is
   * requested in the preferencs.
   *
   * Returns true on success, false on failure. If no location updates are
   * requested as per the app's preferences, that is counted as instant
   * success.
   **/
  public boolean startLocationUpdates()
  {
    // Read preferences.
    SharedPreferences prefs = getPrefs();
    boolean use_location = prefs.getBoolean(PREF_USE_LOCATION, false);
    if (!use_location) {
      // If we're not supposed to use location info, then we'll assume success.
      return true;
    }

    Context ctx = mContext.get();
    if (null == ctx) {
      return false;
    }

    // Determine location provider, if that hasn't happened yet.
    LocationManager lm = (LocationManager) ctx.getSystemService(
        Context.LOCATION_SERVICE);

    // Determine the location provider to use. We prefer GPS, but NETWORK
    // is acceptable.
    mLocationProvider = null;
    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
      mLocationProvider = LocationManager.GPS_PROVIDER;
    }
    else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
      mLocationProvider = LocationManager.NETWORK_PROVIDER;
    }

    if (null == mLocationProvider) {
      Log.w(LTAG, "Location updates requested, but no location provider enabled.");
      return false;
    }
    Log.i(LTAG, "Using location provider: " + mLocationProvider);

    // Start listening to location updates. All we do in the listener is null
    // mLocation if there's any chance it might become stale, and set it when
    // we receive new Location information.
    mLocationListener = new LocationListener()
    {
      public void onLocationChanged(Location location)
      {
        mLocation = location;
        //Log.d(LTAG, "Location accuracy is: " + mLocation.getAccuracy());

        // Determine desired location update period.
        int period = -1;
        if (MAX_LOCATION_ACCURACY > mLocation.getAccuracy()) {
          //Log.d(LTAG, "Location accuracy is sufficient for infrequent updates.");
          period = INFREQUENT_LOCATION_UPDATE_PERIOD;
        }
        else if (MIN_LOCATION_ACCURACY < mLocation.getAccuracy()) {
          //Log.d(LTAG, "Location accuracy is insufficient, switching to frequent attempts.");
          period = FREQUENT_LOCATION_UPDATE_PERIOD;
        }

        // If the period differs from the current period, we'll need to restart
        // listening for updates.
        if (-1 != period && period != mLocationUpdatePeriod) {
          mLocationUpdatePeriod = period;

          Context ctx = mContext.get();
          if (null != ctx) {
            LocationManager locman = (LocationManager) ctx.getSystemService(
                Context.LOCATION_SERVICE);
            if (null != locman) {
              locman.removeUpdates(this);

              Log.i(LTAG, "Changing location update period to " + mLocationUpdatePeriod);
              locman.requestLocationUpdates(mLocationProvider, mLocationUpdatePeriod,
                  LOCATION_UPDATE_RESOLUTION, mLocationListener);
            }
          }
        }
      }


      public void onProviderDisabled(String provider)
      {
        mLocation = null;
      }


      public void onProviderEnabled(String provider)
      {
        mLocation = null;
      }


      public void onStatusChanged(String provider, int status, Bundle extras)
      {
        if (status == LocationProvider.OUT_OF_SERVICE) {
          mLocation = null;
        }
      }
    };

    mLocationUpdatePeriod = FREQUENT_LOCATION_UPDATE_PERIOD;
    Log.i(LTAG, "Starting location updates with period " + mLocationUpdatePeriod);
    lm.requestLocationUpdates(mLocationProvider, mLocationUpdatePeriod,
        LOCATION_UPDATE_RESOLUTION, mLocationListener);

    return true;
  }



  /**
   * Stops listeneing to updates to the devie's location.
   **/
  public void stopLocationUpdates()
  {
    if (null == mLocationListener) {
      return;
    }

    Context ctx = mContext.get();
    if (null == ctx) {
      return;
    }

    LocationManager lm = (LocationManager) ctx.getSystemService(
        Context.LOCATION_SERVICE);
    lm.removeUpdates(mLocationListener);

    mLocationListener = null;
  }



  /**
   * Returns an error message corresponding to an error code.
   * See API.java, res/values/errors.xml and res/values/localized.xml for
   * details.
   **/
  public String getErrorMessage(int code)
  {
    if (null == mErrorMessages) {
      Context ctx = mContext.get();
      if (null == ctx) {
        return null;
      }

      int[] codes = ctx.getResources().getIntArray(R.array.error_codes);
      String[] messages = ctx.getResources().getStringArray(R.array.error_messages);

      if (codes.length != messages.length || 0 == codes.length) {
        Log.e(LTAG, "Programmer error: the error code and error messages arrays"
            + " are of different sizes, or don't exist.");
        return null;
      }

      HashMap<Integer, String> messagemap = new HashMap<Integer, String>();
      for (int i = 0 ; i < codes.length ; ++i) {
        messagemap.put(codes[i], messages[i]);
      }

      mErrorMessages = messagemap;
    }

    return mErrorMessages.get(code);
  }



  /**
   * Creates Dialog instances for the Dialog IDs defined above. Dialogs belong
   * to the Activity passed as the first parameter.
   **/
  public Dialog createDialog(final Activity activity, int id)
  {
    return createDialog(activity, id, -1, null);
  }


  public Dialog createDialog(final Activity activity, int id, int code,
      API.APIException exception)
  {
    return createDialog(activity, id, code, exception, null);
  }


  public Dialog createDialog(final Activity activity, int id, int code,
      API.APIException exception, DialogInterface.OnClickListener error_ack_listener)
  {
    Dialog dialog = null;

    Resources res = activity.getResources();

    switch (id) {
      case DIALOG_GPS_SETTINGS:
        {
          AlertDialog.Builder builder = new AlertDialog.Builder(activity);
          builder.setMessage(res.getString(R.string.gps_settings_message))
            .setCancelable(false)
            .setPositiveButton(res.getString(R.string.gps_settings_start),
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int id) {
                    Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    activity.startActivity(i);
                  }
                })
            .setNegativeButton(res.getString(R.string.gps_settings_cancel),
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                  }
                });
          dialog = builder.create();
        }
        break;


      case DIALOG_ERROR:
        {
          // Determine error code and message.
          int report_code = code;
          String message = null;
          if (API.ERR_API_ERROR == code && null != exception) {
            message = exception.getMessage();
            report_code = exception.getCode();
          }
          else {
            message = getErrorMessage(code);
          }

          // Format full text for the dialog.
          String content = String.format(Locale.US, "%s\n\n%s\n\n%s %s",
              res.getString(R.string.error_title),
              res.getString(R.string.error_message_extra),
              res.getString(R.string.error_prefix),
              message);

          // Create dialog
          AlertDialog.Builder builder = new AlertDialog.Builder(activity);
          builder.setMessage(content)
            .setCancelable(false)
            .setPositiveButton(res.getString(R.string.error_message_ack),
                error_ack_listener);
          dialog = builder.create();
        }
        break;
    }

    return dialog;
  }



  /**
   * Return the global BooManager instance.
   **/
  public BooManager getBooManager()
  {
    if (null == mBooManager) {
      Context ctx = mContext.get();
      if (null == ctx) {
        return null;
      }

      List<String> paths = new LinkedList<String>();

	Log.d(LTAG, "BooManager looking for exernal storage.");
      // Data on SD card; first preferred path.
	  String base;
	  try {
      	base = Environment.getExternalStorageDirectory().getPath();
		Log.d(LTAG, "Got:  " + base);
		File d = new File(base);
			d.listFiles();	// see if we can actually access what's given to us.  If not then fall back to internal storage
			if (!d.canWrite()) throw new IOException(); 
	  }
	  catch (Exception ex)
	  {
		Log.d(LTAG, "Failed to read back form external sdcard.  Falling back to internal storage.  Caught exception " + ex.getMessage());
		base = ctx.getCacheDir().getPath();
	  }

      	base += File.separator + "data" + File.separator + ctx.getPackageName() + File.separator + "boos";
      	paths.add(base);

      // Private data, for compatibility with old versions and fallbacks.
      base = ctx.getDir(DATA_DIR_PREFIX, Context.MODE_PRIVATE).getPath();
      paths.add(base);

      mBooManager = new BooManager(paths);
    }

    return mBooManager;
  }



  /**
   * Return the recording time limit.
   **/
  public int getRecordingLimit()
  {
    // Grab the recording limit from the preferences first.
    SharedPreferences prefs = getPrefs();
    if (null != prefs) {
      return prefs.getInt(PREF_RECORDING_TIME, API.Status.DEFAULT_RECORD_DURATION);
    }

    return API.Status.DEFAULT_RECORD_DURATION;
  }



  /**
   * Return current linked status of the app
   **/
  public API.Status getStatus()
  {
    mStatus = mAPI.getStatus();
    return mStatus;
  }


  /**
   * Update the app's API status.
   **/
  public void updateStatus(final Handler onwardsHandler)
  {
    getStatus();
    mStatusRetries = 0;
    mStatusAuthErrors = 0;
    mOnwardsHandler = onwardsHandler;
    mAccount = null;
    mAPI.updateStatus(mStatusHandler);
  }


  /**
   * Start/stop polling, depending on preferences.
   **/
  public void updatePolling()
  {
    if (null == mStatus || !mStatus.mLinked) {
      // No polling for unlinked accounts.
      return;
    }

    Context ctx = mContext.get();
    if (null == ctx) {
      return;
    }

    AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);

    // Schedule polling?
    int pos = getPrefs().getInt(Constants.PREF_POLL_INTERVAL, 0);
    if (0 == pos) {
      if (null != mPollIntent) {
        am.cancel(mPollIntent);
        mPollIntent = null;
      }
      return;
    }

    Intent poll_intent = new Intent(PollReceiver.ACTION_POLL_MESSAGES);
    mPollIntent = PendingIntent.getBroadcast(ctx, 0, poll_intent, 0);

    long interval = POLL_INTERVALS[pos - 1];
    long delay = SystemClock.elapsedRealtime() + interval;
    am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, delay, interval, mPollIntent);
  }
}
