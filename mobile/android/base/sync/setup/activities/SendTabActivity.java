/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.goanna.sync.setup.activities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.mozilla.goanna.R;
import org.mozilla.goanna.background.common.log.Logger;
import org.mozilla.goanna.sync.CommandProcessor;
import org.mozilla.goanna.sync.CommandRunner;
import org.mozilla.goanna.sync.GlobalSession;
import org.mozilla.goanna.sync.SyncConfiguration;
import org.mozilla.goanna.sync.SyncConstants;
import org.mozilla.goanna.sync.repositories.NullCursorException;
import org.mozilla.goanna.sync.repositories.android.ClientsDatabaseAccessor;
import org.mozilla.goanna.sync.repositories.domain.ClientRecord;
import org.mozilla.goanna.sync.setup.Constants;
import org.mozilla.goanna.sync.setup.SyncAccounts;
import org.mozilla.goanna.sync.stage.SyncClientsEngineStage;
import org.mozilla.goanna.sync.syncadapter.SyncAdapter;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class SendTabActivity extends Activity {
  public static final String LOG_TAG = "SendTabActivity";
  private ClientRecordArrayAdapter arrayAdapter;
  private AccountManager accountManager;
  private Account localAccount;
  private SendTabData sendTabData;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    try {
      sendTabData = getSendTabData(getIntent());
    } catch (IllegalArgumentException e) {
      notifyAndFinish(false);
      return;
    }

    setContentView(R.layout.sync_send_tab);

    final ListView listview = (ListView) findViewById(R.id.device_list);
    listview.setItemsCanFocus(true);
    listview.setTextFilterEnabled(true);
    listview.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

    arrayAdapter = new ClientRecordArrayAdapter(this, R.layout.sync_list_item);
    listview.setAdapter(arrayAdapter);

    TextView textView = (TextView) findViewById(R.id.title);
    textView.setText(sendTabData.title);

    textView = (TextView) findViewById(R.id.uri);
    textView.setText(sendTabData.uri);

    enableSend(false);

    // will enableSend if appropriate.
    updateClientList();
  }

  protected static SendTabData getSendTabData(Intent intent) throws IllegalArgumentException {
    if (intent == null) {
      Logger.warn(LOG_TAG, "intent was null; aborting without sending tab.");
      throw new IllegalArgumentException();
    }

    Bundle extras = intent.getExtras();
    if (extras == null) {
      Logger.warn(LOG_TAG, "extras was null; aborting without sending tab.");
      throw new IllegalArgumentException();
    }

    SendTabData sendTabData = SendTabData.fromBundle(extras);
    if (sendTabData == null) {
      Logger.warn(LOG_TAG, "send tab data was null; aborting without sending tab.");
      throw new IllegalArgumentException();
    }

    if (sendTabData.uri == null) {
      Logger.warn(LOG_TAG, "uri was null; aborting without sending tab.");
      throw new IllegalArgumentException();
    }

    if (sendTabData.title == null) {
      Logger.warn(LOG_TAG, "title was null; ignoring and sending tab anyway.");
    }

    return sendTabData;
  }

  /**
   * Ensure that the view's list of clients is backed by a recently populated
   * array adapter.
   */
  protected synchronized void updateClientList() {
    // Fetching the client list hits the clients database, so we spin this onto
    // a background task.
    new AsyncTask<Void, Void, Collection<ClientRecord>>() {

      @Override
      protected Collection<ClientRecord> doInBackground(Void... params) {
        return getOtherClients();
      }

      @Override
      protected void onPostExecute(final Collection<ClientRecord> clientArray) {
        // We're allowed to update the UI from here.

        Logger.debug(LOG_TAG, "Got " + clientArray.size() + " clients.");
        arrayAdapter.setClientRecordList(clientArray);
        if (clientArray.size() == 1) {
          arrayAdapter.checkItem(0, true);
        }

        enableSend(arrayAdapter.getNumCheckedGUIDs() > 0);
      }
    }.execute();
  }

  @Override
  public void onResume() {
    ActivityUtils.prepareLogging();
    Logger.info(LOG_TAG, "Called SendTabActivity.onResume.");
    super.onResume();

    redirectIfNoSyncAccount();
    registerDisplayURICommand();
  }

  private static void registerDisplayURICommand() {
    final CommandProcessor processor = CommandProcessor.getProcessor();
    processor.registerCommand("displayURI", new CommandRunner(3) {
      @Override
      public void executeCommand(final GlobalSession session, List<String> args) {
        CommandProcessor.displayURI(args, session.getContext());
      }
    });
  }

  private void redirectIfNoSyncAccount() {
    accountManager = AccountManager.get(getApplicationContext());
    Account[] accts = accountManager.getAccountsByType(SyncConstants.ACCOUNTTYPE_SYNC);

    // A Sync account exists.
    if (accts.length > 0) {
      localAccount = accts[0];
      return;
    }

    Intent intent = new Intent(this, RedirectToSetupActivity.class);
    intent.setFlags(Constants.FLAG_ACTIVITY_REORDER_TO_FRONT_NO_ANIMATION);
    startActivity(intent);
    finish();
  }

  /**
   * @return Return null if there is no account set up. Return the account GUID otherwise.
   */
  private String getAccountGUID() {
    if (localAccount == null) {
      Logger.warn(LOG_TAG, "Null local account; aborting.");
      return null;
    }

    SharedPreferences prefs;
    try {
      prefs = SyncAccounts.blockingPrefsFromDefaultProfileV0(this, accountManager, localAccount);
      return prefs.getString(SyncConfiguration.PREF_ACCOUNT_GUID, null);
    } catch (Exception e) {
      Logger.warn(LOG_TAG, "Could not get Sync account parameters or preferences; aborting.");
      return null;
    }
  }

  public void sendClickHandler(View view) {
    Logger.info(LOG_TAG, "Send was clicked.");
    final List<String> remoteClientGuids = arrayAdapter.getCheckedGUIDs();

    if (remoteClientGuids == null) {
      // Should never happen.
      Logger.warn(LOG_TAG, "guids was null; aborting without sending tab.");
      notifyAndFinish(false);
      return;
    }

    // Fetching local client GUID hits the DB, and we want to update the UI
    // afterward, so we perform the tab sending on another thread.
    new AsyncTask<Void, Void, Boolean>() {

      @Override
      protected Boolean doInBackground(Void... params) {
        final CommandProcessor processor = CommandProcessor.getProcessor();

        final String accountGUID = getAccountGUID();
        Logger.debug(LOG_TAG, "Retrieved local account GUID '" + accountGUID + "'.");
        if (accountGUID == null) {
          return false;
        }

        for (String remoteClientGuid : remoteClientGuids) {
          processor.sendURIToClientForDisplay(sendTabData.uri, remoteClientGuid, sendTabData.title, accountGUID, getApplicationContext());
        }

        Logger.info(LOG_TAG, "Requesting immediate clients stage sync.");
        SyncAdapter.requestImmediateSync(localAccount, new String[] { SyncClientsEngineStage.COLLECTION_NAME });

        return true;
      }

      @Override
      protected void onPostExecute(final Boolean success) {
        // We're allowed to update the UI from here.
        notifyAndFinish(success.booleanValue());
      }
    }.execute();
  }

  /**
   * Notify the user about sent tabs status and then finish the activity.
   * <p>
   * "Success" is a bit of a misnomer: we wrote "displayURI" commands to the local
   * command database, and they will be sent on next sync. There is no way to
   * verify that the commands were successfully received by the intended remote
   * client, so we lie and say they were sent.
   *
   * @param success true if tab was sent successfully; false otherwise.
   */
  protected void notifyAndFinish(final boolean success) {
    int textId;
    if (success) {
      textId = R.string.sync_text_tab_sent;
    } else {
      textId = R.string.sync_text_tab_not_sent;
    }

    Toast.makeText(this, textId, Toast.LENGTH_LONG).show();
    finish();
  }

  public void enableSend(boolean shouldEnable) {
    View sendButton = findViewById(R.id.send_button);
    sendButton.setEnabled(shouldEnable);
    sendButton.setClickable(shouldEnable);
  }

  /**
   * @return a map from GUID to client record, including our own.
   */
  protected Map<String, ClientRecord> getAllClients() {
    ClientsDatabaseAccessor db = new ClientsDatabaseAccessor(this.getApplicationContext());
    try {
      return db.fetchAllClients();
    } catch (NullCursorException e) {
      Logger.warn(LOG_TAG, "NullCursorException while populating device list.", e);
      return null;
    } finally {
      db.close();
    }
  }

  /**
   * @return a collection of client records, excluding our own.
   */
  protected Collection<ClientRecord> getOtherClients() {
    final Map<String, ClientRecord> all = getAllClients();
    if (all == null) {
      return new ArrayList<ClientRecord>(0);
    }

    final String ourGUID = getAccountGUID();
    if (ourGUID == null) {
      return all.values();
    }

    final ArrayList<ClientRecord> out = new ArrayList<ClientRecord>(all.size());
    for (Entry<String, ClientRecord> entry : all.entrySet()) {
      if (ourGUID.equals(entry.getKey())) {
        continue;
      }
      out.add(entry.getValue());
    }
    return out;
  }
}
