/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintLayout;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.account.RedditAccountManager;
import org.quantumbadger.redreader.activities.BaseActivity;
import org.quantumbadger.redreader.cache.CacheManager;
import org.quantumbadger.redreader.cache.CacheRequest;
import org.quantumbadger.redreader.cache.CacheRequestCallbacks;
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyIfNotCached;
import org.quantumbadger.redreader.common.AndroidCommon;
import org.quantumbadger.redreader.common.Constants;
import org.quantumbadger.redreader.common.DisplayUtils;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.GenericFactory;
import org.quantumbadger.redreader.common.Optional;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.common.Priority;
import org.quantumbadger.redreader.common.RRError;
import org.quantumbadger.redreader.common.SharedPrefsWrapper;
import org.quantumbadger.redreader.common.datastream.SeekableInputStream;
import org.quantumbadger.redreader.common.time.TimestampUTC;
import org.quantumbadger.redreader.fragments.PostListingFragment;
import org.quantumbadger.redreader.reddit.api.RedditPostActions;
import org.quantumbadger.redreader.reddit.prepared.RedditParsedPost;
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedPost;
import org.quantumbadger.redreader.views.liststatus.ErrorView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class RedditPostView extends FlingableItemView
		implements RedditPreparedPost.ThumbnailLoadedCallback {

	private static final String TAG = "RedditPostView";

	private static final String PROMPT_PREF_KEY = "inline_image_prompt_accepted";

	private static final AtomicInteger sInlinePreviewsShownThisSession = new AtomicInteger(0);

	private final AccessibilityActionManager mAccessibilityActionManager;

	private RedditPreparedPost mPost = null;
	private final TextView title;
	private final TextView subtitle;

	@NonNull private final ImageView mThumbnailView;
	@NonNull private final ImageView mOverlayIcon;

	@NonNull private final LinearLayout mOuterView;
	@NonNull private final LinearLayout mInnerView;
	@NonNull private final LinearLayout mCommentsButton;
	@NonNull private final TextView mCommentsText;
	@NonNull private final LinearLayout mPostErrors;
	@NonNull private final FrameLayout mImagePreviewHolder;
	@NonNull private final ImageView mImagePreviewImageView;
	@NonNull private final ConstraintLayout mImagePreviewPlayOverlay;
	@NonNull private final LinearLayout mImagePreviewOuter;
	@NonNull private final LoadingSpinnerView mImagePreviewLoadingSpinner;
	@NonNull private final LinearLayout mFooter;

	private int mUsageId = 0;

	private final Handler thumbnailHandler;

	private final BaseActivity mActivity;

	private final PrefsUtility.PostFlingAction mLeftFlingPref;
	private final PrefsUtility.PostFlingAction mRightFlingPref;
	private RedditPostActions.ActionDescriptionPair mLeftFlingAction;
	private RedditPostActions.ActionDescriptionPair mRightFlingAction;

	private final boolean mCommentsButtonPref;

	private final int
			rrPostTitleReadCol;
	private final int rrPostTitleCol;

	private final int mThumbnailSizePrefPixels;

	@Override
	protected void onSetItemFlingPosition(final float position) {
		mOuterView.setTranslationX(position);
	}

	@NonNull
	@Override
	protected String getFlingLeftText() {

		mLeftFlingAction = RedditPostActions.ActionDescriptionPair.from(mPost, mLeftFlingPref);

		if(mLeftFlingAction != null) {
			return mActivity.getString(mLeftFlingAction.getDescriptionRes());
		} else {
			return "Disabled";
		}
	}

	@NonNull
	@Override
	protected String getFlingRightText() {

		mRightFlingAction = RedditPostActions.ActionDescriptionPair.from(mPost, mRightFlingPref);

		if(mRightFlingAction != null) {
			return mActivity.getString(mRightFlingAction.getDescriptionRes());
		} else {
			return "Disabled";
		}
	}

	@Override
	protected boolean allowFlingingLeft() {
		return mLeftFlingAction != null;
	}

	@Override
	protected boolean allowFlingingRight() {
		return mRightFlingAction != null;
	}

	@Override
	protected void onFlungLeft() {
		RedditPostActions.INSTANCE.onActionMenuItemSelected(
				mPost,
				mActivity,
				mLeftFlingAction.getAction());
	}

	@Override
	protected void onFlungRight() {
		RedditPostActions.INSTANCE.onActionMenuItemSelected(
				mPost,
				mActivity,
				mRightFlingAction.getAction());
	}



	public RedditPostView(
			final Context context,
			final PostListingFragment fragmentParent,
			final BaseActivity activity,
			final boolean leftHandedMode) {

		super(context);
		mActivity = activity;

		mAccessibilityActionManager = new AccessibilityActionManager(
				this,
				context.getResources());

		thumbnailHandler = new Handler(Looper.getMainLooper()) {
			@Override
			public void handleMessage(@NonNull final Message msg) {
				if(mUsageId != msg.what) {
					return;
				}
				mThumbnailView.setImageBitmap((Bitmap)msg.obj);
			}
		};

		final float dpScale = context.getResources().getDisplayMetrics().density;

		final float titleFontScale = PrefsUtility.appearance_fontscale_posts();
		final float subtitleFontScale = PrefsUtility.appearance_fontscale_post_subtitles();

		final View rootView =
				LayoutInflater.from(context).inflate(R.layout.reddit_post, this, true);

		mOuterView = Objects.requireNonNull(rootView.findViewById(R.id.reddit_post_layout_outer));
		mInnerView = Objects.requireNonNull(rootView.findViewById(R.id.reddit_post_layout_inner));

		mPostErrors = Objects.requireNonNull(rootView.findViewById(R.id.reddit_post_errors));

		mImagePreviewHolder = Objects.requireNonNull(
				rootView.findViewById(R.id.reddit_post_image_preview_holder));

		mImagePreviewImageView = Objects.requireNonNull(
				rootView.findViewById(R.id.reddit_post_image_preview_imageview));

		mImagePreviewPlayOverlay = Objects.requireNonNull(
				rootView.findViewById(R.id.reddit_post_image_preview_play_overlay));

		mImagePreviewOuter = Objects.requireNonNull(
				rootView.findViewById(R.id.reddit_post_image_preview_outer));

		mFooter = Objects.requireNonNull(
				rootView.findViewById(R.id.reddit_post_footer));

		mImagePreviewLoadingSpinner = new LoadingSpinnerView(activity);
		mImagePreviewHolder.addView(mImagePreviewLoadingSpinner);

		mThumbnailView = Objects.requireNonNull(
				rootView.findViewById(R.id.reddit_post_thumbnail_view));

		mOverlayIcon = Objects.requireNonNull(
				rootView.findViewById(R.id.reddit_post_overlay_icon));

		title = Objects.requireNonNull(rootView.findViewById(R.id.reddit_post_title));
		subtitle = Objects.requireNonNull(rootView.findViewById(R.id.reddit_post_subtitle));

		mCommentsButtonPref =
				PrefsUtility.appearance_post_show_comments_button();

		mCommentsButton = rootView.findViewById(R.id.reddit_post_comments_button);
		mCommentsText = mCommentsButton.findViewById(R.id.reddit_post_comments_text);

		if(!mCommentsButtonPref) {
			mInnerView.removeView(mCommentsButton);
		}

		if(leftHandedMode) {
			final ArrayList<View> innerViewElements = new ArrayList<>(3);
			for(int i = mInnerView.getChildCount() - 1; i >= 0; i--) {
				innerViewElements.add(mInnerView.getChildAt(i));
				mInnerView.removeViewAt(i);
			}

			for(int i = 0; i < innerViewElements.size(); i++) {
				mInnerView.addView(innerViewElements.get(i));
			}

			mInnerView.setNextFocusRightId(NO_ID);
			if (mCommentsButtonPref) {
				mInnerView.setNextFocusLeftId(mCommentsButton.getId());

				mCommentsButton.setNextFocusForwardId(R.id.reddit_post_layout_outer);
				mCommentsButton.setNextFocusRightId(R.id.reddit_post_layout_outer);
				mCommentsButton.setNextFocusLeftId(NO_ID);
			}
		}

		final OnLongClickListener longClickListener = v -> {
			RedditPostActions.INSTANCE.showActionMenu(mActivity, mPost);
			return true;
		};

		switch(PrefsUtility.pref_behaviour_post_tap_action()) {
			case LINK:
				mOuterView.setOnClickListener(v -> fragmentParent.onPostSelected(mPost));
				AndroidCommon.removeClickListeners(mThumbnailView);
				AndroidCommon.removeClickListeners(mImagePreviewOuter);
				AndroidCommon.removeClickListeners(title);
				break;

			case COMMENTS:
				mOuterView.setOnClickListener(v -> fragmentParent.onPostCommentsSelected(mPost));

				mThumbnailView.setOnClickListener(v -> fragmentParent.onPostSelected(mPost));
				mThumbnailView.setOnLongClickListener(longClickListener);

				mImagePreviewOuter.setOnClickListener(v -> fragmentParent.onPostSelected(mPost));
				mImagePreviewOuter.setOnLongClickListener(longClickListener);

				AndroidCommon.removeClickListeners(title);

				break;

			case TITLE_COMMENTS:
				mOuterView.setOnClickListener(v -> fragmentParent.onPostSelected(mPost));

				AndroidCommon.removeClickListeners(mThumbnailView);
				AndroidCommon.removeClickListeners(mImagePreviewOuter);

				title.setOnClickListener(v -> fragmentParent.onPostCommentsSelected(mPost));
				title.setOnLongClickListener(longClickListener);
				break;
		}

		mOuterView.setOnLongClickListener(longClickListener);

		if(mCommentsButtonPref) {
			mCommentsButton.setOnClickListener(v -> fragmentParent.onPostCommentsSelected(mPost));
		}

		title.setTextSize(
				TypedValue.COMPLEX_UNIT_PX,
				title.getTextSize() * titleFontScale);
		subtitle.setTextSize(
				TypedValue.COMPLEX_UNIT_PX,
				subtitle.getTextSize() * subtitleFontScale);

		mLeftFlingPref =
				PrefsUtility.pref_behaviour_fling_post_left();
		mRightFlingPref =
				PrefsUtility.pref_behaviour_fling_post_right();

		{
			final TypedArray attr = context.obtainStyledAttributes(new int[] {
					R.attr.rrPostTitleCol,
					R.attr.rrPostTitleReadCol,
			});

			rrPostTitleCol = attr.getColor(0, 0);
			rrPostTitleReadCol = attr.getColor(1, 0);
			attr.recycle();
		}

		mThumbnailSizePrefPixels = (int)(dpScale * PrefsUtility.images_thumbnail_size_dp());
	}

	@UiThread
	public void reset(@NonNull final RedditPreparedPost newPost) {

		if(newPost != mPost) {

			mThumbnailView.setImageBitmap(null);
			mImagePreviewImageView.setImageBitmap(null);
			mImagePreviewPlayOverlay.setVisibility(GONE);
			mPostErrors.removeAllViews();
			mFooter.removeAllViews();

			mUsageId++;

			resetSwipeState();

			title.setText(newPost.src.getTitle());
			if(mCommentsButtonPref) {
				mCommentsText.setText(String.valueOf(newPost.src.getSrc().getNum_comments()));
			}

			final boolean showInlinePreview = newPost.shouldShowInlinePreview();

			final boolean showThumbnail = !showInlinePreview && newPost.hasThumbnail;

			if(showInlinePreview) {
				downloadInlinePreview(newPost, mUsageId);

			} else {
				mImagePreviewLoadingSpinner.setVisibility(GONE);
				mImagePreviewOuter.setVisibility(GONE);
				setBottomMargin(false);
			}

			if(showThumbnail) {

				final Bitmap thumbnail = newPost.getThumbnail(this, mUsageId);
				mThumbnailView.setImageBitmap(thumbnail);

				mThumbnailView.setVisibility(VISIBLE);
				mThumbnailView.setMinimumWidth(mThumbnailSizePrefPixels);

				General.setLayoutWidthHeight(
						mThumbnailView,
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.MATCH_PARENT);

				mInnerView.setMinimumHeight(mThumbnailSizePrefPixels);

			} else {
				mThumbnailView.setMinimumWidth(0);
				mThumbnailView.setVisibility(GONE);
				mInnerView.setMinimumHeight(General.dpToPixels(mActivity, 64));
			}
		}

		if(mPost != null) {
			mPost.unbind(this);
		}

		newPost.bind(this);

		mPost = newPost;

		updateAppearance();
	}

	public void updateAppearance() {

		mOuterView.setBackgroundResource(
				R.drawable.rr_postlist_item_selector_main);

		if(mCommentsButtonPref) {
			mCommentsButton.setBackgroundResource(
					R.drawable.rr_postlist_commentbutton_selector_main);
		}

		if(mPost.isRead()) {
			title.setTextColor(rrPostTitleReadCol);
		} else {
			title.setTextColor(rrPostTitleCol);
		}

		title.setContentDescription(mPost.buildAccessibilityTitle(mActivity, false));

		subtitle.setText(mPost.buildSubtitle(mActivity, false));
		subtitle.setContentDescription(mPost.buildAccessibilitySubtitle(mActivity, false));

		boolean overlayVisible = true;

		if(mPost.isSaved()) {
			mOverlayIcon.setImageResource(R.drawable.star_dark);

		} else if(mPost.isHidden()) {
			mOverlayIcon.setImageResource(R.drawable.ic_action_cross_dark);

		} else if(mPost.isUpvoted()) {
			mOverlayIcon.setImageResource(R.drawable.arrow_up_bold_orangered);

		} else if(mPost.isDownvoted()) {
			mOverlayIcon.setImageResource(R.drawable.arrow_down_bold_periwinkle);

		} else {
			overlayVisible = false;
		}

		if(overlayVisible) {
			mOverlayIcon.setVisibility(VISIBLE);
		} else {
			mOverlayIcon.setVisibility(GONE);
		}

		RedditPostActions.INSTANCE.setupAccessibilityActions(
				mAccessibilityActionManager,
				mPost,
				mActivity,
				false);
	}

	@Override
	public void betterThumbnailAvailable(
			final Bitmap thumbnail,
			final int callbackUsageId) {
		final Message msg = Message.obtain();
		msg.obj = thumbnail;
		msg.what = callbackUsageId;
		thumbnailHandler.sendMessage(msg);
	}

	public interface PostSelectionListener {
		void onPostSelected(RedditPreparedPost post);

		void onPostCommentsSelected(RedditPreparedPost post);
	}

	private void setBottomMargin(final boolean enabled) {

		final MarginLayoutParams layoutParams
				= (MarginLayoutParams)mOuterView.getLayoutParams();

		if(enabled) {
			layoutParams.bottomMargin = General.dpToPixels(mActivity, 6);
		} else {
			layoutParams.bottomMargin = 0;
		}

		mOuterView.setLayoutParams(layoutParams);
	}

	private void downloadInlinePreview(
			@NonNull final RedditPreparedPost post,
			final int usageId) {

		final Rect windowVisibleDisplayFrame
				= DisplayUtils.getWindowVisibleDisplayFrame(mActivity);

		final int screenWidth = Math.min(1080, Math.max(720, windowVisibleDisplayFrame.width()));
		final int screenHeight = Math.min(2000, Math.max(400, windowVisibleDisplayFrame.height()));

		final RedditParsedPost.ImagePreviewDetails preview
				= post.src.getPreview(screenWidth, 0);

		if(preview == null || preview.width < 10 || preview.height < 10) {
			mImagePreviewOuter.setVisibility(GONE);
			mImagePreviewLoadingSpinner.setVisibility(GONE);
			setBottomMargin(false);
			return;
		}

		final int boundedImageHeight = Math.min(
				(screenHeight * 2) / 3,
				(int)(((long)preview.height * screenWidth) / preview.width));

		final ConstraintLayout.LayoutParams imagePreviewLayoutParams
				= (ConstraintLayout.LayoutParams)mImagePreviewHolder.getLayoutParams();

		imagePreviewLayoutParams.dimensionRatio = screenWidth + ":" + boundedImageHeight;
		mImagePreviewHolder.setLayoutParams(imagePreviewLayoutParams);

		mImagePreviewOuter.setVisibility(VISIBLE);
		mImagePreviewLoadingSpinner.setVisibility(VISIBLE);
		setBottomMargin(true);

		CacheManager.getInstance(mActivity).makeRequest(new CacheRequest.Builder()
				.setUrl(preview.url)
				.setUser(RedditAccountManager.getAnon())
				.setPriority(new Priority(Constants.Priority.INLINE_IMAGE_PREVIEW))
				.setDownloadStrategy(DownloadStrategyIfNotCached.INSTANCE)
				.setFileType(Constants.FileType.INLINE_IMAGE_PREVIEW)
				.setQueueType(CacheRequest.DownloadQueueType.IMMEDIATE)
				.setRequestMethod(CacheRequest.RequestMethod.GET)
				.setContext(mActivity)
				.setCache(true)
				.setCallbacks(new CacheRequestCallbacks() {
					@Override
					public void onDataStreamComplete(
							@NonNull final GenericFactory<SeekableInputStream, IOException> stream,
							final TimestampUTC timestamp,
							@NonNull final UUID session,
							final boolean fromCache,
							@Nullable final String mimetype) {

						if(usageId != mUsageId) {
							return;
						}

						try(InputStream is = stream.create()) {

							final Bitmap data = BitmapFactory.decodeStream(is);

							if(data == null) {
								throw new IOException("Failed to decode bitmap");
							}

							// Avoid a crash on badly behaving Android ROMs (where the ImageView
							// crashes if an image is too big)
							// Should never happen as we limit the preview size to 3000x3000
							if(data.getByteCount() > 50 * 1024 * 1024) {
								throw new RuntimeException("Image was too large: "
										+ data.getByteCount()
										+ ", preview URL was "
										+ preview.url
										+ " and post was "
										+ post.src.getIdAndType());
							}

							final boolean alreadyAcceptedPrompt = General.getSharedPrefs(mActivity)
									.getBoolean(PROMPT_PREF_KEY, false);

							final int totalPreviewsShown
									= sInlinePreviewsShownThisSession.incrementAndGet();

							final boolean isVideoPreview = post.isVideoPreview();

							AndroidCommon.runOnUiThread(() -> {
								mImagePreviewImageView.setImageBitmap(data);
								mImagePreviewLoadingSpinner.setVisibility(GONE);

								if(isVideoPreview) {
									mImagePreviewPlayOverlay.setVisibility(VISIBLE);
								}

								// Show every 8 previews, starting at the second one
								if(totalPreviewsShown % 8 == 2 && !alreadyAcceptedPrompt) {
									showPrefPrompt();
								}
							});

						} catch(final Throwable t) {
							onFailure(General.getGeneralErrorForFailure(
									mActivity,
									CacheRequest.RequestFailureType.CONNECTION,
									t,
									null,
									preview.url,
									Optional.empty()));
						}
					}

					@Override
					public void onFailure(@NonNull final RRError error) {

						Log.e(TAG, "Failed to download image preview: " + error, error.t);

						if(usageId != mUsageId) {
							return;
						}

						AndroidCommon.runOnUiThread(() -> {

							mImagePreviewLoadingSpinner.setVisibility(GONE);
							mImagePreviewOuter.setVisibility(GONE);

							final ErrorView errorView = new ErrorView(
									mActivity,
									error);

							mPostErrors.addView(errorView);
							General.setLayoutMatchWidthWrapHeight(errorView);
						});
					}
				})
				.build());
	}

	private void showPrefPrompt() {

		final SharedPrefsWrapper sharedPrefs
				= General.getSharedPrefs(mActivity);

		LayoutInflater.from(mActivity).inflate(
				R.layout.inline_images_question_view,
				mFooter,
				true);

		final FrameLayout promptView
				= mFooter.findViewById(R.id.inline_images_prompt_root);

		final Button keepShowing
				= mFooter.findViewById(R.id.inline_preview_prompt_keep_showing_button);

		final Button turnOff
				= mFooter.findViewById(R.id.inline_preview_prompt_turn_off_button);

		keepShowing.setOnClickListener(v -> {

			new RRAnimationShrinkHeight(promptView).start();

			sharedPrefs.edit()
					.putBoolean(PROMPT_PREF_KEY, true)
					.apply();
		});

		turnOff.setOnClickListener(v -> {

			final String prefPreview = mActivity.getApplicationContext()
					.getString(
							R.string.pref_images_inline_image_previews_key);

			sharedPrefs.edit()
					.putBoolean(PROMPT_PREF_KEY, true)
					.putString(prefPreview, "never")
					.apply();
		});
	}
}
