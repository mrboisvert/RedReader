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

package org.quantumbadger.redreader.cache;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.quantumbadger.redreader.activities.BugReportActivity;
import org.quantumbadger.redreader.common.Constants;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.Optional;
import org.quantumbadger.redreader.common.PrioritisedCachedThreadPool;
import org.quantumbadger.redreader.common.Priority;
import org.quantumbadger.redreader.common.TorCommon;
import org.quantumbadger.redreader.common.datastream.MemoryDataStream;
import org.quantumbadger.redreader.common.time.TimestampUTC;
import org.quantumbadger.redreader.http.FailedRequestBody;
import org.quantumbadger.redreader.http.HTTPBackend;
import org.quantumbadger.redreader.image.RedgifsAPIV2;
import org.quantumbadger.redreader.reddit.api.RedditOAuth;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CacheDownload extends PrioritisedCachedThreadPool.Task {

	private static final String TAG = "CacheDownload";

	private final CacheRequest mInitiator;
	private final CacheManager manager;
	private final UUID session;

	private volatile boolean mCancelled = false;
	private static final AtomicBoolean resetUserCredentials = new AtomicBoolean(false);
	private final HTTPBackend.Request mRequest;

	public CacheDownload(
			final CacheRequest initiator,
			final CacheManager manager) {

		this.mInitiator = initiator;

		this.manager = manager;

		if(!initiator.setDownload(this)) {
			mCancelled = true;
		}

		if(initiator.requestSession != null) {
			session = initiator.requestSession;
		} else {
			session = UUID.randomUUID();
		}

		mRequest = HTTPBackend.getBackend().prepareRequest(
				initiator.context,
				new HTTPBackend.RequestDetails(
						mInitiator.url,
						mInitiator.requestMethod,
						mInitiator.requestBody.asNullable()));
	}

	public synchronized void cancel() {

		mCancelled = true;

		new Thread() {
			@Override
			public void run() {
				if(mRequest != null) {
					mRequest.cancel();
					mInitiator.notifyFailure(General.getGeneralErrorForFailure(
							mInitiator.context,
							CacheRequest.RequestFailureType.CANCELLED,
							null,
							null,
							mInitiator.url,
							Optional.empty()));
				}
			}
		}.start();
	}

	public void doDownload() {

		if(mCancelled) {
			return;
		}

		try {
			performDownload(mRequest);

		} catch(final Throwable t) {
			BugReportActivity.handleGlobalError(mInitiator.context, t);
		}
	}

	public static void resetUserCredentialsOnNextRequest() {
		resetUserCredentials.set(true);
	}

	private void performDownload(final HTTPBackend.Request request) {

		if(mInitiator.queueType == CacheRequest.DownloadQueueType.REDDIT_API) {

			if(resetUserCredentials.getAndSet(false)) {
				mInitiator.user.setAccessToken(null);
			}

			RedditOAuth.AccessToken accessToken
					= mInitiator.user.getMostRecentAccessToken();

			if(accessToken == null || accessToken.isExpired()) {

				mInitiator.notifyProgress(true, 0, 0);

				final RedditOAuth.FetchAccessTokenResult result;

				if(mInitiator.user.isAnonymous()) {
					result = RedditOAuth.fetchAnonymousAccessTokenSynchronous(mInitiator.context);

				} else {
					result = RedditOAuth.fetchAccessTokenSynchronous(
							mInitiator.context,
							mInitiator.user);
				}

				if(result.status != RedditOAuth.FetchAccessTokenResultStatus.SUCCESS) {
					mInitiator.notifyFailure(result.error);
					return;
				}

				accessToken = result.accessToken;
				mInitiator.user.setAccessToken(accessToken);
			}

			request.addHeader("Authorization", "bearer " + accessToken.token);
		}

		if(mInitiator.queueType == CacheRequest.DownloadQueueType.IMGUR_API) {
			request.addHeader("Authorization", "Client-ID c3713d9e7674477");

		} else if(mInitiator.queueType == CacheRequest.DownloadQueueType.REDGIFS_API_V2) {
			request.addHeader("Authorization", "Bearer " + RedgifsAPIV2.getLatestToken());
		}

		mInitiator.notifyDownloadStarted();

		request.executeInThisThread(new HTTPBackend.Listener() {
			@Override
			public void onError(
					@NonNull final CacheRequest.RequestFailureType failureType,
					final Throwable exception,
					final Integer httpStatus,
					@Nullable final FailedRequestBody body) {
				if(mInitiator.queueType == CacheRequest.DownloadQueueType.REDDIT_API
						&& TorCommon.isTorEnabled()) {
					HTTPBackend.getBackend().recreateHttpBackend();
					resetUserCredentialsOnNextRequest();
				}

				mInitiator.notifyFailure(General.getGeneralErrorForFailure(
						mInitiator.context,
						failureType,
						exception,
						httpStatus,
						mInitiator.url,
						Optional.ofNullable(body)));
			}

			@Override
			public void onSuccess(
					final String mimetype,
					final Long bodyBytes,
					final InputStream is) {

				if(mCancelled) {
					Log.i(TAG, "Request cancelled at start of onSuccess()");
					return;
				}

				final MemoryDataStream stream = new MemoryDataStream(64 * 1024);

				mInitiator.notifyDataStreamAvailable(
						stream::getInputStream,
						TimestampUTC.now(),
						session,
						false,
						mimetype);

				// Download the file into memory

				try {

					final byte[] buf = new byte[64 * 1024];

					int bytesRead;
					long totalBytesRead = 0;

					while((bytesRead = tryReadFully(is, buf)) > 0) {

						totalBytesRead += bytesRead;

						stream.writeBytes(buf, 0, bytesRead);

						if(bodyBytes != null) {
							mInitiator.notifyProgress(
									false,
									totalBytesRead,
									bodyBytes);
						}

						if(mCancelled) {
							Log.i(TAG, "Request cancelled during read loop");
							stream.setFailed(new IOException("Download cancelled"));
							return;
						}
					}

					stream.setComplete();

					mInitiator.notifyDataStreamComplete(
							stream::getInputStream,
							TimestampUTC.now(),
							session,
							false,
							mimetype);

				} catch(final Throwable t) {

					stream.setFailed(t instanceof IOException
							? (IOException)t
							: new IOException("Got exception during download", t));

					mInitiator.notifyFailure(General.getGeneralErrorForFailure(
							mInitiator.context,
							CacheRequest.RequestFailureType.CONNECTION,
							t,
							null,
							mInitiator.url,
							Optional.empty()));

					return;

				} finally {
					General.closeSafely(is);
				}

				// Save it to the cache

				if(mInitiator.cache) {

					@NonNull final CacheManager.WritableCacheFile writableCacheFile;

					final CacheCompressionType cacheCompressionType;

					switch(mInitiator.fileType) {
						case Constants.FileType.CAPTCHA:
						case Constants.FileType.IMAGE:
						case Constants.FileType.INLINE_IMAGE_PREVIEW:
						case Constants.FileType.NOCACHE:
						case Constants.FileType.THUMBNAIL:
							// Image saving/sharing relies the file on disk being "raw"
							cacheCompressionType = CacheCompressionType.NONE;
							break;

						case Constants.FileType.COMMENT_LIST:
						case Constants.FileType.IMAGE_INFO:
						case Constants.FileType.INBOX_LIST:
						case Constants.FileType.MULTIREDDIT_LIST:
						case Constants.FileType.POST_LIST:
						case Constants.FileType.SUBREDDIT_ABOUT:
						case Constants.FileType.SUBREDDIT_LIST:
						case Constants.FileType.USER_ABOUT:
							cacheCompressionType = CacheCompressionType.ZSTD;
							break;

						default:
							Log.e(TAG, "Unhandled filetype: " + mInitiator.fileType);
							cacheCompressionType = CacheCompressionType.NONE;
					}

					try {
						writableCacheFile = manager.openNewCacheFile(
								mInitiator.url,
								mInitiator.user,
								mInitiator.fileType,
								session,
								mimetype,
								cacheCompressionType);

					} catch(final IOException e) {

						Log.e(TAG, "Exception opening cache file for write", e);

						final CacheRequest.RequestFailureType failureType;

						if(manager.getPreferredCacheLocation().exists()) {
							failureType = CacheRequest.RequestFailureType.STORAGE;
						} else {
							failureType = CacheRequest
									.RequestFailureType.CACHE_DIR_DOES_NOT_EXIST;
						}

						mInitiator.notifyFailure(General.getGeneralErrorForFailure(
								mInitiator.context,
								failureType,
								e,
								null,
								mInitiator.url,
								Optional.empty()));

						return;
					}

					try {
						stream.getUnderlyingByteArrayWhenComplete(
								writableCacheFile::writeWholeFile);

						writableCacheFile.onWriteFinished();

						mInitiator.notifyCacheFileWritten(
								writableCacheFile.getReadableCacheFile(),
								TimestampUTC.now(),
								session,
								false,
								mimetype);

					} catch(final IOException e) {

						writableCacheFile.onWriteCancelled();

						mInitiator.notifyFailure(General.getGeneralErrorForFailure(
								mInitiator.context,
								CacheRequest.RequestFailureType.STORAGE,
								e,
								null,
								mInitiator.url,
								Optional.empty()));
					}
				}
			}
		});
	}

	@NonNull
	@Override
	public Priority getPriority() {
		return mInitiator.priority;
	}

	@Override
	public void run() {
		doDownload();
	}

	private static int tryReadFully(
			final InputStream src,
			final byte[] dst
	) throws IOException {
		int totalBytesRead = 0;

		while(true) {
			final int bytesRead = src.read(dst, totalBytesRead, dst.length - totalBytesRead);

			if (bytesRead <= 0) {
				return totalBytesRead;
			}

			totalBytesRead += bytesRead;

			if (totalBytesRead >= dst.length) {
				return totalBytesRead;
			}
		}
	}
}
