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

import android.widget.BaseExpandableListAdapter;

import android.app.ExpandableListActivity;

import android.content.res.Resources;
import android.content.res.ColorStateList;

import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import android.widget.TextView;
import android.widget.ImageView;
import android.widget.AbsListView;

import android.net.Uri;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;

import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;

import java.util.List;
import java.util.LinkedList;
import java.util.Locale;

import fm.audioboo.data.User;

import java.lang.ref.WeakReference;

import android.util.Log;

/**
 * Adapter for presenting a BooList in a ListView, Gallery or similar.
 **/
public class BooListAdapter extends BaseExpandableListAdapter
{
  /***************************************************************************
   * Private constants
   **/
  // Log ID
  private static final String LTAG  = "BooListAdapter";


  /***************************************************************************
   * Public constants
   **/
  // Item view types
  public static final int VIEW_TYPE_UNKNOWN   = -1;
  public static final int VIEW_TYPE_BOO       = 0;
  public static final int VIEW_TYPE_MORE      = 1;
  public static final int VIEW_TYPE_UPLOAD    = 2;

  // List item elements
  public static final int ELEMENT_AUTHOR      = 0;
  public static final int ELEMENT_TITLE       = 1;
  public static final int ELEMENT_LOCATION    = 2;
  public static final int ELEMENT_PROGRESS    = 3;


  /***************************************************************************
   * Data source interface
   **/
  public static interface DataSource
  {
    /**
     * Returns the number of groups in the data source.
     **/
    public int getGroupCount();

    /**
     * If this returns true, the Adapter hides the group view for this group.
     **/
    public boolean hideGroup(int group);

    /**
     * Retursn group data for the given group, or null if the group parameter
     * was out of range.
     **/
    public List<Boo> getGroup(int group);

    /**
     * Return true if the given group paginates, false otherwise.
     **/
    public boolean doesPaginate(int group);

    /**
     * Returns the group label for the given group.
     **/
    public String getGroupLabel(int group);

    /**
     * Returns the background resource for the given view type.
     **/
    public int getBackgroundResource(int viewType);

    /**
     * Returns the state drawable resource for coloring text elements.
     **/
    public int getElementColor(int element);

    /**
     * Return the view type for a given group. Usually expected to be
     * VIEW_TYPE_BOO; perhaps VIEW_TYPE_UPLOAD.
     **/
    public int getGroupType(int group);
  }



  /***************************************************************************
   * Helper class. Makes the BooListAdapter aware of relevant changes in
   * the scroll state of the ListView it's filling, so it can trigger the
   * or cancel heavy lifiting such as downloading images.
   **/
  public static class ScrollListener implements AbsListView.OnScrollListener
  {
    private BooListAdapter  mAdapter;

    private boolean         mSentInitial = false;
    private int             mFirst;
    private int             mCount;


    public ScrollListener(BooListAdapter adapter)
    {
      mAdapter = adapter;
    }



    public void onScroll(AbsListView view, int firstVisibleItem,
        int visibleItemCount, int totalItemCount)
    {
      mFirst = firstVisibleItem;
      mCount = visibleItemCount;

      if (!mSentInitial && visibleItemCount > 0) {
        mSentInitial = true;
        mAdapter.startHeavyLifting(mFirst, mCount);
      }
    }



    public void onScrollStateChanged(AbsListView view, int scrollState)
    {
      if (SCROLL_STATE_IDLE != scrollState) {
        mAdapter.cancelHeavyLifting();
        return;
      }

      mAdapter.startHeavyLifting(mFirst, mCount);
    }
  }



  /***************************************************************************
   * Cache baton
   **/
  private class Baton
  {
    public Pair<Integer, Integer> itemIndex;
    public int                    viewIndex;

    Baton(Pair<Integer, Integer> _itemIndex, int _viewIndex)
    {
      itemIndex = _itemIndex;
      viewIndex = _viewIndex;
    }
  }


  /***************************************************************************
   * Upload view animation
   **/
  private class UploadAnimation extends Animation
  {
    private long                mLastDraw = 0;
    private String              mBooFilename;
    private WeakReference<View> mView;



    public UploadAnimation(View view, String filename)
    {
      mView = new WeakReference<View>(view);
      mBooFilename = filename;
    }



    @Override
    public boolean getTransformation(long currentTime, Transformation outTransformation)
    {
      boolean ret = super.getTransformation(currentTime, outTransformation);

      View view = mView.get();
      if (null == view) {
        return ret;
      }

      ExpandableListActivity activity = mActivity.get();
      if (null == activity) {
        return ret;
      }

      long draw = SystemClock.uptimeMillis();
      if (draw - mLastDraw > 1000) {
        mLastDraw = draw;

        TextView text_view = (TextView) view.findViewById(R.id.boo_list_upload_progress);
        if (null == text_view) {
          view.clearAnimation();
          return ret;
        }

        // We need to re-read the Boo file in order to get the latest upload
        // information.
        Boo boo = Boo.constructFromFile(mBooFilename);

        // Status string we're writing.
        String status = null;

        if (null == boo) {
          // We treat this case as "completed". It's in the list, but it's not
          // on disk any longer.
          status = activity.getResources().getString(R.string.boo_list_upload_done);
          view.clearAnimation();
        }
        else if (null == boo.mData.mUploadInfo) {
          // Just ignore.
          return ret;
        }
        else if (boo.mData.mUploadInfo.mUploadError) {
          // Error.
          status = activity.getResources().getString(R.string.boo_list_upload_error);
        }
        else {
          // Update progress
          double progress = boo.uploadProgress();
          if (progress > 0.0) {
            status = String.format(Locale.US, activity.getResources().getString(R.string.boo_list_upload_progress), progress);
          }
          else {
            status = activity.getResources().getString(R.string.boo_list_upload_pending);
          }
        }

        text_view.setText(status);
      }

      return ret;
    }
  }



  /***************************************************************************
   * Data
   **/
  // Boo data
  private WeakReference<DataSource>             mData;

  // Layouts.
  private int[]                                 mLayouts;

  // Flag, for deciding whether the "more" view should be shown as loading or
  // not.
  private boolean                               mLoading = false;

  // Calling Activity
  private WeakReference<ExpandableListActivity> mActivity;

  // Image dimensions. XXX The (reasonably safe) assumption is that in the view
  // this adapter fills, all Boo images are to be displayed at the same size.
  private int                                   mDimensions = -1;

  // "Hidden" group view.
  private View                                  mHiddenView;

  // Flag indicating whether initial group expansion has occurred or not.
  private boolean                               mInitialized = false;

  // Handler for disclosure clicks
  private View.OnClickListener                  mDisclosureListener;


  /***************************************************************************
   * Implementation
   **/
  /**
   * The layouts array is expected to contain:
   * - At index 0, the layout for boos.
   * - At index 1, if the data source contains groups whose group view is not
   *   to be hidden, the group view layout.
   * - At index 2, if any of the groups in the data source paginate, the "more"
   *   view layout.
   * If the data source represents e.g. one paginating group with a hidden group
   * view, the array must still be 3 in size, but index 1 will not be consulted.
   **/
  public BooListAdapter(ExpandableListActivity activity, DataSource data,
      int[] layouts)
  {
    super();

    mActivity = new WeakReference<ExpandableListActivity>(activity);
    mData = new WeakReference<DataSource>(data);
    mLayouts = layouts;
  }



  void setDisclosureListener(View.OnClickListener listener)
  {
    mDisclosureListener = listener;
  }



  /**
   * Maps a single position (child-in-list) to a group/positon-in-group
   * pair.
   **/
  public Pair<Integer, Integer> mapPosition(int position)
  {
    DataSource data = mData.get();
    if (null == data) {
      return null;
    }

    int offset = 0;

    for (int group = 0 ; group < data.getGroupCount() ; ++group) {
      List<Boo> boos = data.getGroup(group);
      int group_size = 0;
      if (null != boos) {
        group_size = boos.size();
      }

      int first = offset + 1;
      int last = first + group_size;
      if (data.doesPaginate(group)) {
        last += 1;
      }

      if (position >= first && position < last) {
        // Got the mapping!
        return new Pair<Integer, Integer>(group, position - first);
      }

      // No mapping in this group; increase offset.
      offset = last;
    }

    // No mapping!
    return null;
  }



  /**
   * Maps a group/position-in-group pair to a single position.
   **/
  public int mapPosition(Pair<Integer, Integer> compound)
  {
    return mapPosition(compound.mFirst, compound.mSecond);
  }

  public int mapPosition(int group, int position)
  {
    DataSource data = mData.get();
    if (null == data) {
      return -1;
    }

    int offset = 0;

    for (int g = 0 ; g < group ; ++g) {
      List<Boo> boos = data.getGroup(g);
      int group_size = boos.size();

      offset += 1 + group_size;
      if (data.doesPaginate(g)) {
        offset += 1;
      }
    }

    if (data.doesPaginate(group)) {
      offset += 1;
    }

    offset += position;
    return offset;
  }



  public View getGroupView(int group, boolean isExpanded, View convertView, ViewGroup parent)
  {
    ExpandableListActivity activity = mActivity.get();
    if (null == activity) {
      return null;
    }

    DataSource data = mData.get();
    if (null == data) {
      return null;
    }

    // First things first: expand all groups, if this is the first request.
    if (!mInitialized) {
      mInitialized = true;
      for (int i = 0 ; i < data.getGroupCount() ; ++i) {
        activity.getExpandableListView().expandGroup(i);
      }
    }

    // Log.d(LTAG, "wants group view for position: " + group + " - " + isExpanded);

    // If the group is to be hidden, return the hidden view.
    if (data.hideGroup(group)) {
      // Return mHiddenView; it might need to be initialized first.
      if (null == mHiddenView) {
        mHiddenView = new View(activity);
      }
      return mHiddenView;
    }

    // Otherwise create/populate a proper group view.
    View view = convertView;
    if (null == view) {
      LayoutInflater inflater = activity.getLayoutInflater();
      view = inflater.inflate(mLayouts[1], null);
    }

    TextView text_view = (TextView) view.findViewById(R.id.group_label);
    if (null != text_view) {
      text_view.setText(data.getGroupLabel(group));
    }

    return view;
  }



  public View getChildView(int group, int position, boolean isLast,
      View convertView, ViewGroup parent)
  {
    // Log.d(LTAG, "get child view: " + group + " / " + position);

    ExpandableListActivity activity = mActivity.get();
    if (null == activity) {
      return null;
    }

    DataSource data = mData.get();
    if (null == data) {
      return null;
    }

    // View type
    int type = getChildType(group, position);
    if (VIEW_TYPE_UNKNOWN == type) {
      Log.e(LTAG, "Unknown view type detected.");
      return null;
    }
    // Log.d(LTAG, "type: " + type);

    // Create new view, if required.
    View view = convertView;
    if (null == view) {
      LayoutInflater inflater = activity.getLayoutInflater();
      switch (type) {
        case VIEW_TYPE_BOO:
          view = inflater.inflate(mLayouts[0], null);
          break;

        case VIEW_TYPE_MORE:
          view = inflater.inflate(mLayouts[2], null);
          break;

        case VIEW_TYPE_UPLOAD:
          view = inflater.inflate(mLayouts[3], null);
          break;

        default:
          // XXX Unreachable.
          return null;
      }
    }


    boolean ret = false;
    switch (type) {
      case VIEW_TYPE_BOO:
        ret = prepareBooView(activity, data, group, position, view);
        break;

      case VIEW_TYPE_MORE:
        ret = prepareMoreView(activity, data, view);
        break;

      case VIEW_TYPE_UPLOAD:
        ret = prepareUploadView(activity, data, group, position, view);
        break;

      default:
        // XXX Unreachable.
        return null;
    }

    if (!ret) {
      return null;
    }

    return view;
  }



  public int getChildTypeCount()
  {
    DataSource data = mData.get();
    if (null == data) {
      return 0;
    }

    int ret = 2;
    for (int i = 0 ; i < data.getGroupCount() ; ++i) {
      if (data.doesPaginate(i)) {
        ret += 1;
        break;
      }
    }

    return ret;
  }



  public int getChildType(int group, int position)
  {
    DataSource data = mData.get();
    if (null == data) {
      return VIEW_TYPE_UNKNOWN;
    }

    List<Boo> boos = data.getGroup(group);
    if (null == boos) {
      return VIEW_TYPE_MORE;
    }

    if (!data.doesPaginate(group) || position < boos.size()) {
      return data.getGroupType(group);
    }
    return VIEW_TYPE_MORE;
  }



  public int getChildrenCount(int group)
  {
    DataSource data = mData.get();
    if (null == data) {
      return 0;
    }

    List<Boo> boos = data.getGroup(group);
    if (null == boos) {
      return 1;
    }

    int ret = boos.size();
    if (data.doesPaginate(group)) {
      ret += 1;
    }

    // Log.d(LTAG, "child count for group " + group + ": " + ret);
    return ret;
  }



  public long getChildId(int group, int position)
  {
    Boo boo = (Boo) getChild(group, position);
    if (null == boo || null == boo.mData) {
      return -1;
    }
    return boo.mData.mId;
  }



  public Object getChild(int group, int position)
  {
    DataSource data = mData.get();
    if (null == data) {
      return null;
    }

    List<Boo> boos = data.getGroup(group);
    if (null == boos) {
      return null;
    }

    if (position >= boos.size()) {
      return null;
    }
    return boos.get(position);
  }



  private void setTextColor(ExpandableListActivity activity, DataSource data, TextView text_view, int element)
  {
    int colorId = data.getElementColor(element);
    if (-1 != colorId) {
      ColorStateList color = activity.getResources().getColorStateList(colorId);
      text_view.setTextColor(color);
    }
  }



  private void cancelHeavyLifting()
  {
    Globals.get().mImageCache.cancelFetching();
  }



  private void startHeavyLifting(int first, int count)
  {
    // Log.d(LTAG, "Downloads for items from " + first + " to " + (first + count));

    DataSource data = mData.get();
    if (null == data) {
      return;
    }

    // Prepare the list of uris to download.
    LinkedList<ImageCache.CacheItem> uris = new LinkedList<ImageCache.CacheItem>();
    for (int i = 0 ; i < count ; ++i) {
      int index = first + i;
      Pair<Integer, Integer> mapped = mapPosition(index);
      if (null == mapped) {
        continue;
      }

      List<Boo> boos = data.getGroup(mapped.mFirst);
      if (null == boos) {
        continue;
      }

      if (mapped.mSecond < 0 || mapped.mSecond >= boos.size()) {
        continue;
      }

      Boo boo = boos.get(mapped.mSecond);
      Pair<String, Uri> uri = getDisplayUrl(boo);

      if (null == uri) {
        continue;
      }

      uris.add(new ImageCache.CacheItem(uri.mSecond, mDimensions,
            new Baton(mapped, i), uri.mFirst));
    }

    if (0 < uris.size()) {
      // Use the cache to fetch these images.
      Globals.get().mImageCache.fetch(uris, new Handler(new Handler.Callback() {
        public boolean handleMessage(Message msg)
        {
          ImageCache.CacheItem item = (ImageCache.CacheItem) msg.obj;
          switch (msg.what) {
            case ImageCache.MSG_OK:
              onCacheItemFetched(item);
              break;

            case ImageCache.MSG_CANCELLED:
              //Log.d(LTAG, "Download of image cancelled: " + item.mImageUri);
              break;

            case ImageCache.MSG_ERROR:
            default:
              Log.e(LTAG, "Error fetching image at URL: " + (item != null ? item.mImageUri : null));
              break;
          }
          return true;
        }
      }));
    }
  }



  public void onCacheItemFetched(ImageCache.CacheItem item)
  {
    // Log.d(LTAG, "got results for : " + item.mImageUri);
    ExpandableListActivity activity = mActivity.get();
    if (null == activity) {
      return;
    }

    DataSource data = mData.get();
    if (null == data) {
      return;
    }

    Baton baton = (Baton) item.mBaton;

    // Right, we got an image. Now we just need to figure out the right view
    // to go with it.
    View item_view = activity.getExpandableListView().getChildAt(baton.viewIndex);
    if (null == item_view) {
      return;
    }

    // Make sure that the item for which the request was scheduled and the
    // item we're displaying currently are the same. That's what we set the
    // view's tag for in getChildView().
    List<Boo> boos = data.getGroup(baton.itemIndex.mFirst);
    if (null == boos) {
      return;
    }
    if (baton.itemIndex.mSecond >= boos.size()) {
      return;
    }
    Boo expected_boo = boos.get(baton.itemIndex.mSecond);
    @SuppressWarnings("unchecked")
    Pair<Boo, Integer> current = (Pair<Boo, Integer>) item_view.getTag();
    if (null != current && null != current.mFirst && null != current.mFirst.mData
        && expected_boo.mData.mId != current.mFirst.mData.mId)
    {
      // There's been a race between cancelling downloads and sending the
      // message with the resulting bitmap.
      return;
    }

    // Now display the image.
    ImageView image_view = (ImageView) item_view.findViewById(R.id.boo_list_item_image);
    if (null == image_view) {
      return;
    }

    image_view.setImageDrawable(new BitmapDrawable(item.mBitmap));
  }



  private Pair<String, Uri> getDisplayUrl(Boo boo)
  {
    if (null == boo) {
      return null;
    }

    Uri result = boo.mData.getThumbUrl();
    if (null == result && null != boo.mData.mUser) {
      result = boo.mData.mUser.getThumbUrl();
    }

    if (null == result) {
      return null;
    }

    String cacheKey = result.toString();

    // If the result is relative (i.e. has no authority), then we need to make
    // it absolute. Also, we need to sign it.
    if (null == result.getAuthority()) {
      result = Globals.get().mAPI.makeAbsoluteUri(result);
      result = Globals.get().mAPI.signUri(result);
    }

    return new Pair<String, Uri>(cacheKey, result);
  }



  public void setLoading(boolean loading, View view)
  {
    mLoading = loading;
    if (null != view) {
      ExpandableListActivity activity = mActivity.get();
      if (null == activity) {
        return;
      }

      DataSource data = mData.get();
      if (null == data) {
        return;
      }


      prepareMoreView(activity, data, view);
    }
  }



  private boolean prepareBooView(ExpandableListActivity activity, DataSource data, int group, int position, View view)
  {
    // Set the view's tag to the Boo we want to display. This is for later
    // identification.
    List<Boo> boos = data.getGroup(group);
    if (position < 0 || position >= boos.size()) {
      Log.e(LTAG, "Position " + position + " out of range for group " + group);
      return false;
    }
    Boo boo = boos.get(position);
    view.setTag(new Pair<Boo, Integer>(boo, group));

    // Select the view's background.
    int bgId = data.getBackgroundResource(VIEW_TYPE_BOO);
    if (-1 != bgId) {
      view.setBackgroundResource(bgId);
    }

    // Fill view with data.
    TextView text_view = (TextView) view.findViewById(R.id.boo_list_item_author);
    if (null != text_view) {
      setTextColor(activity, data, text_view, ELEMENT_AUTHOR);

      if (null != boo.mData.mUser && null != boo.mData.mUser.mUsername) {
        text_view.setText(boo.mData.mUser.mUsername);
      }
      else {
        if (!boo.isLocal()) {
          text_view.setText(activity.getResources().getString(R.string.boo_unknown_author));
        }
        else {
          User account = Globals.get().mAccount;
          if (null != account && null != account.mUsername) {
            text_view.setText(account.mUsername);
          }
          else {
            text_view.setText(R.string.boo_author_self);
          }
        }
      }
    }

    text_view = (TextView) view.findViewById(R.id.boo_list_item_title);
    if (null != text_view) {
      setTextColor(activity, data, text_view, ELEMENT_TITLE);

      if (null != boo.mData.mTitle) {
        text_view.setText(boo.mData.mTitle);
      }
      else {
        text_view.setText(activity.getResources().getString(R.string.boo_unknown_title));
      }
    }

    text_view = (TextView) view.findViewById(R.id.boo_list_item_location);
    if (null != text_view) {
      setTextColor(activity, data, text_view, ELEMENT_LOCATION);

      if (null != boo.mData.mLocation && null != boo.mData.mLocation.mDescription) {
        text_view.setText(boo.mData.mLocation.mDescription);
      }
      else {
        text_view.setText("");
      }
    }

    // If the image cache contains an appropriate image at the right size, then
    // we'll display that. If not, display a default image. We need to do the
    // second in case the item view is being reused.
    ImageView image_view = (ImageView) view.findViewById(R.id.boo_list_item_image);
    if (null != image_view) {
      // First, determine the url we want to display.
      Pair<String, Uri> image_url = getDisplayUrl(boo);

      boolean customImageSet = false;
      if (null != image_url) {
        // If we don't know the image dimensions yet, determine them now.
        if (-1 == mDimensions) {
          mDimensions = image_view.getLayoutParams().width
            - image_view.getPaddingLeft() - image_view.getPaddingRight();
        }

        // Next, try to grab an image from the cache.
        Bitmap bitmap = Globals.get().mImageCache.get(image_url.mFirst, mDimensions);

        if (null != bitmap) {
          image_view.setImageDrawable(new BitmapDrawable(bitmap));
          customImageSet = true;
        }
      }

      // If we were not able to set a custom image here, default to the
      // anonymous_boo one.
      if (!customImageSet) {
        image_view.setImageResource(R.drawable.anonymous_boo);
      }
    }

    // Disclosure
    View disclosure = view.findViewById(R.id.boo_list_item_disclosure);
    if (null != disclosure && null != mDisclosureListener) {
      disclosure.setTag(new Pair<Boo, Integer>(boo, group));
      disclosure.setOnClickListener(mDisclosureListener);
    }

    return true;
  }



  private boolean prepareMoreView(ExpandableListActivity activity, DataSource data, View view)
  {
    // Select the view's background.
    int bgId = data.getBackgroundResource(VIEW_TYPE_MORE);
    if (-1 != bgId) {
      view.setBackgroundResource(bgId);
    }

    // Show progress/dots
    View swap = view.findViewById(R.id.boo_list_more_dots);
    if (null != swap) {
      swap.setVisibility(mLoading ? View.GONE : View.VISIBLE);
    }

    swap = view.findViewById(R.id.boo_list_more_progress);
    if (null != swap) {
      swap.setVisibility(mLoading ? View.VISIBLE : View.GONE);
    }

    return true;
  }



  private boolean prepareUploadView(ExpandableListActivity activity, DataSource data,
      int group, int position, View view)
  {
    // Grab upload.
    List<Boo> boos = data.getGroup(group);
    if (position < 0 || position >= boos.size()) {
      Log.e(LTAG, "Position " + position + " out of range for group " + group);
      return false;
    }
    Boo boo = boos.get(position);

    // Select the view's background.
    int bgId = data.getBackgroundResource(VIEW_TYPE_UPLOAD);
    if (-1 != bgId) {
      view.setBackgroundResource(bgId);
    }

    // Fill view with data.
    TextView text_view = (TextView) view.findViewById(R.id.boo_list_upload_author);
    if (null != text_view) {
      setTextColor(activity, data, text_view, ELEMENT_AUTHOR);

      if (null != boo.mData.mUser && null != boo.mData.mUser.mUsername) {
        text_view.setText(boo.mData.mUser.mUsername);
      }
      else {
        String username = activity.getResources().getString(R.string.boo_list_me);
        if (null != Globals.get().mAccount) {
          username = Globals.get().mAccount.mUsername;
        }
        text_view.setText(username);
      }
    }

    text_view = (TextView) view.findViewById(R.id.boo_list_upload_title);
    if (null != text_view) {
      setTextColor(activity, data, text_view, ELEMENT_TITLE);

      if (null != boo.mData.mTitle) {
        text_view.setText(boo.mData.mTitle);
      }
      else {
        text_view.setText(activity.getResources().getString(R.string.boo_unknown_title));
      }
    }

    text_view = (TextView) view.findViewById(R.id.boo_list_upload_progress);
    if (null != text_view) {
      setTextColor(activity, data, text_view, ELEMENT_PROGRESS);

      double progress = boo.uploadProgress();
      if (progress > 0.0) {
        text_view.setText(String.format(Locale.US, activity.getResources().getString(R.string.boo_list_upload_progress),
              progress));
      }
      else {
        text_view.setText(activity.getResources().getString(R.string.boo_list_upload_pending));
      }
    }

    // If the image cache contains an appropriate image at the right size, then
    // we'll display that. If not, display a default image. We need to do the
    // second in case the item view is being reused.
    ImageView image_view = (ImageView) view.findViewById(R.id.boo_list_item_image);
    if (null != image_view) {
      // First, determine the url we want to display.
      Pair<String, Uri> image_url = getDisplayUrl(boo);

      boolean customImageSet = false;
      if (null != image_url) {
        // If we don't know the image dimensions yet, determine them now.
        if (-1 == mDimensions) {
          mDimensions = image_view.getLayoutParams().width
            - image_view.getPaddingLeft() - image_view.getPaddingRight();
        }

        // Next, try to grab an image from the cache.
        Bitmap bitmap = Globals.get().mImageCache.get(image_url.mFirst, mDimensions);

        if (null != bitmap) {
          image_view.setImageDrawable(new BitmapDrawable(bitmap));
          customImageSet = true;
        }
      }

      // If we were not able to set a custom image here, default to the
      // anonymous_boo one.
      if (!customImageSet) {
        image_view.setImageResource(R.drawable.anonymous_boo);
      }
    }

    // Start an animation for this view. The animation updates the progress.
    view.clearAnimation();
    UploadAnimation anim = new UploadAnimation(view, boo.mData.mFilename);
    anim.setRepeatCount(Animation.INFINITE);
    anim.setDuration(1000L);
    view.startAnimation(anim);

    return true;
  }


  public boolean isChildSelectable(int groupPosition, int childPosition)
  {
    // By default, all children are selectable.
    return true;
  }



  public boolean hasStableIds()
  {
    // By default, IDs are stable.
    return true;
  }



  public long getGroupId(int group)
  {
    // Make things simple: group index and ID are the same.
    return group;
  }



  public Object getGroup(int group)
  {
    DataSource data = mData.get();
    if (null == data) {
      return null;
    }
    return data.getGroup(group);
  }



  public int getGroupCount()
  {
    DataSource data = mData.get();
    if (null == data) {
      return 0;
    }
    return data.getGroupCount();
  }
}
