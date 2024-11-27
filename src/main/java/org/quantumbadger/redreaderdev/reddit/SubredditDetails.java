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

package org.quantumbadger.redreaderdev.reddit;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.quantumbadger.redreaderdev.R;
import org.quantumbadger.redreaderdev.activities.HtmlViewActivity;
import org.quantumbadger.redreaderdev.common.HasUniqueId;
import org.quantumbadger.redreaderdev.common.PrefsUtility;
import org.quantumbadger.redreaderdev.common.UriString;
import org.quantumbadger.redreaderdev.reddit.things.InvalidSubredditNameException;
import org.quantumbadger.redreaderdev.reddit.things.RedditSubreddit;
import org.quantumbadger.redreaderdev.reddit.things.SubredditCanonicalId;

import java.util.Locale;

public class SubredditDetails implements HasUniqueId {

	@NonNull public final SubredditCanonicalId id;
	@NonNull public final String name;
	@NonNull public final UriString url;
	@Nullable public final String publicDescriptionHtmlEscaped;
	@Nullable public final Integer subscribers;

	public SubredditDetails(
			@NonNull final RedditSubreddit subreddit) throws InvalidSubredditNameException {
		id = subreddit.getCanonicalId();
		name = subreddit.display_name;
		url = subreddit.getUrl();
		publicDescriptionHtmlEscaped = subreddit.public_description_html;
		subscribers = subreddit.subscribers;
	}

	public SubredditDetails(@NonNull final SubredditCanonicalId subreddit) {
		id = subreddit;
		name = subreddit.getDisplayNameLowercase();
		url = new UriString(subreddit.toString());
		publicDescriptionHtmlEscaped = null;
		subscribers = null;
	}

	@NonNull
	public static SubredditDetails newWithRuntimeException(
			@NonNull final RedditSubreddit subreddit) {

		try {
			return new SubredditDetails(subreddit);
		} catch(final InvalidSubredditNameException e) {
			throw new RuntimeException(e);
		}
	}

	@NonNull
	@Override
	public String getUniqueId() {
		return id.toString();
	}

	public boolean hasSidebar() {
		return publicDescriptionHtmlEscaped != null && !publicDescriptionHtmlEscaped.isEmpty();
	}

	public void showSidebarActivity(final AppCompatActivity context) {

		final Intent intent = new Intent(context, HtmlViewActivity.class);

		intent.putExtra("html", RedditSubreddit.getSidebarHtmlStatic(
				PrefsUtility.isNightMode(),
				publicDescriptionHtmlEscaped));

		intent.putExtra("title", String.format(
				Locale.US, "%s: %s",
				context.getString(R.string.sidebar_activity_title),
				url));

		context.startActivityForResult(intent, 1);
	}
}
