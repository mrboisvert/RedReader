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

package org.quantumbadger.redreader.fragments;

import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.account.RedditAccountManager;
import org.quantumbadger.redreader.cache.CacheManager;
import org.quantumbadger.redreader.common.RRError;
import org.quantumbadger.redreader.common.TimestampBound;
import org.quantumbadger.redreader.reddit.APIResponseHandler;
import org.quantumbadger.redreader.reddit.RedditAPI;
import org.quantumbadger.redreader.reddit.api.RedditMultiredditSubscriptionManager;
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId;
import org.quantumbadger.redreader.views.liststatus.ErrorView;

import java.util.ArrayList;
import java.util.Collections;

public class AddToMultiredditDialog {

	private final static String TAG = "AddToMultiredditDialog";

	public static void show(
			final AppCompatActivity activity,
			final SubredditCanonicalId subredditCanonicalId) {

		final MaterialAlertDialogBuilder alertBuilder
				= new MaterialAlertDialogBuilder(activity);

		final View root = activity.getLayoutInflater().inflate(
				R.layout.add_to_multireddit,
				null);

		final RedditMultiredditSubscriptionManager multiredditManager
				= RedditMultiredditSubscriptionManager.getSingleton(
				activity,
				RedditAccountManager.getInstance(activity).getDefaultAccount());

		final ArrayList<String> multireddits = multiredditManager.getSubscriptionList();
		Collections.sort(multireddits);

		final ArrayAdapter<String> autocompleteAdapter = new ArrayAdapter<>(
				activity,
				android.R.layout.simple_dropdown_item_1line,
				multireddits);

		final AutoCompleteTextView editText
				= root.findViewById(R.id.selected_multireddit);
		editText.setAdapter(autocompleteAdapter);

		alertBuilder.setView(root);

		alertBuilder.setNegativeButton(R.string.dialog_cancel, null);

		alertBuilder.setPositiveButton(
				R.string.dialog_go,
				(dialog, which) -> addToMultireddit(activity, editText, subredditCanonicalId));

		final AlertDialog alertDialog = alertBuilder.create();

		editText.setOnEditorActionListener((v, actionId, event) -> {
			if(actionId == EditorInfo.IME_ACTION_GO
					|| event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
				addToMultireddit(activity, editText, subredditCanonicalId);
				alertDialog.dismiss();
			}
			return false;
		});

		alertDialog.getWindow()
				.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
						| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
		alertDialog.show();
	}

	private static void addToMultireddit(
			final AppCompatActivity activity,
			final AutoCompleteTextView editText,
			final SubredditCanonicalId subredditCanonicalId) {

		final String multiredditName = editText.getText()
				.toString()
				.trim()
				.replace(" ", "");
		final String subredditName = subredditCanonicalId.getDisplayNameLowercase();

		RedditAPI.addSubredditToMultireddit(
				CacheManager.getInstance(activity),
				new APIResponseHandler.ActionResponseHandler(activity) {

					@Override
					protected void onCallbackException(final Throwable t) {
						Log.e(
								TAG, "Error while adding subreddit to multireddit", t);
						throw new RuntimeException(t);
					}

					@Override
					protected void onFailure(@NonNull final RRError error) {
						activity.runOnUiThread(() -> {
							final MaterialAlertDialogBuilder builder
									= new MaterialAlertDialogBuilder(activity);
							builder.setView(new ErrorView(activity, error));
							builder.create().show();
						});
					}

					@Override
					protected void onSuccess() {
						activity.runOnUiThread(() -> Toast.makeText(
								activity,
								String.format("Added %s to %s", subredditName, multiredditName),
								Toast.LENGTH_SHORT).show());
						RedditMultiredditSubscriptionManager.getSingleton(
										activity,
										RedditAccountManager
												.getInstance(activity).getDefaultAccount())
								.triggerUpdate(null, TimestampBound.NONE);
					}
				},
				RedditAccountManager.getInstance(activity).getDefaultAccount(),
				multiredditName,
				subredditName,
				activity
		);

		Toast.makeText(
				activity,
				String.format("Adding %s to %s", subredditName, multiredditName),
				Toast.LENGTH_SHORT).show();
	}
}