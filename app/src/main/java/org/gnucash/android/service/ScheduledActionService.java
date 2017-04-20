/*
 * Copyright (c) 2015 Ngewi Fet <ngewif@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gnucash.android.service;

import android.app.IntentService;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.PowerManager;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.crashlytics.android.Crashlytics;

import org.gnucash.android.app.GnuCashApplication;
import org.gnucash.android.db.DatabaseHelper;
import org.gnucash.android.db.DatabaseSchema;
import org.gnucash.android.db.adapter.BooksDbAdapter;
import org.gnucash.android.db.adapter.DatabaseAdapter;
import org.gnucash.android.db.adapter.RecurrenceDbAdapter;
import org.gnucash.android.db.adapter.ScheduledActionDbAdapter;
import org.gnucash.android.db.adapter.SplitsDbAdapter;
import org.gnucash.android.db.adapter.TransactionsDbAdapter;
import org.gnucash.android.export.ExportAsyncTask;
import org.gnucash.android.export.ExportFormat;
import org.gnucash.android.export.ExportParams;
import org.gnucash.android.export.xml.GncXmlExporter;
import org.gnucash.android.model.Book;
import org.gnucash.android.model.ScheduledAction;
import org.gnucash.android.model.Transaction;
import org.gnucash.android.util.BookUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPOutputStream;

/**
 * Service for running scheduled events.
 * <p>The service is started and goes through all scheduled event entries in the the database and executes them.
 * Then it is stopped until the next time it is run. <br>
 * Scheduled runs of the service should be achieved using an {@link android.app.AlarmManager}</p>
 * @author Ngewi Fet <ngewif@gmail.com>
 */
public class ScheduledActionService extends IntentService {

    public static final String LOG_TAG = "ScheduledActionService";

    public ScheduledActionService() {
        super(LOG_TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.i(LOG_TAG, "Starting scheduled action service");

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
        wakeLock.acquire();

        autoBackup(); //First run automatic backup of all books before doing anything else
        try {
            BooksDbAdapter booksDbAdapter = BooksDbAdapter.getInstance();
            List<Book> books = booksDbAdapter.getAllRecords();
            for (Book book : books) { //// TODO: 20.04.2017 Retrieve only the book UIDs with new method
                DatabaseHelper dbHelper = new DatabaseHelper(GnuCashApplication.getAppContext(), book.getUID());
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                RecurrenceDbAdapter recurrenceDbAdapter = new RecurrenceDbAdapter(db);
                ScheduledActionDbAdapter scheduledActionDbAdapter = new ScheduledActionDbAdapter(db, recurrenceDbAdapter);

                List<ScheduledAction> scheduledActions = scheduledActionDbAdapter.getAllEnabledScheduledActions();
                Log.i(LOG_TAG, String.format("Processing %d total scheduled actions for Book: %s",
                        scheduledActions.size(), book.getDisplayName()));
                processScheduledActions(scheduledActions, db);

                //close all databases except the currently active database
                if (!db.getPath().equals(GnuCashApplication.getActiveDb().getPath()))
                    db.close();
            }

            Log.i(LOG_TAG, "Completed service @ " + java.text.DateFormat.getDateTimeInstance().format(new Date()));

        } finally { //release the lock either way
            wakeLock.release();
        }
    }

    /**
     * Process scheduled actions and execute any pending actions
     * @param scheduledActions List of scheduled actions
     */
    //made public static for testing. Do not call these methods directly
    @VisibleForTesting
    public static void processScheduledActions(List<ScheduledAction> scheduledActions, SQLiteDatabase db) {
        for (ScheduledAction scheduledAction : scheduledActions) {

            long now        = System.currentTimeMillis();
            int totalPlannedExecutions = scheduledAction.getTotalPlannedExecutionCount();
            int executionCount = scheduledAction.getExecutionCount();

            //the end time of the ScheduledAction is not handled here because
            //it is handled differently for transactions and backups. See the individual methods.
            if (scheduledAction.getStartTime() > now    //if schedule begins in the future
                    || !scheduledAction.isEnabled()     // of if schedule is disabled
                    || (totalPlannedExecutions > 0 && executionCount >= totalPlannedExecutions)) { //limit was set and we reached or exceeded it
                Log.i(LOG_TAG, "Skipping scheduled action: " + scheduledAction.toString());
                continue;
            }

            executeScheduledEvent(scheduledAction, db);
        }
    }

    /**
     * Executes a scheduled event according to the specified parameters
     * @param scheduledAction ScheduledEvent to be executed
     */
    private static void executeScheduledEvent(ScheduledAction scheduledAction, SQLiteDatabase db){
        Log.i(LOG_TAG, "Executing scheduled action: " + scheduledAction.toString());
        int executionCount = 0;

        switch (scheduledAction.getActionType()){
            case TRANSACTION:
                executionCount += executeTransactions(scheduledAction, db);
                break;

            case BACKUP:
                executionCount += executeBackup(scheduledAction, db);
                break;
        }

        if (executionCount > 0) {
            scheduledAction.setLastRun(System.currentTimeMillis());
            // Set the execution count in the object because it will be checked
            // for the next iteration in the calling loop.
            // This call is important, do not remove!!
            scheduledAction.setExecutionCount(scheduledAction.getExecutionCount() + executionCount);
            // Update the last run time and execution count
            ContentValues contentValues = new ContentValues();
            contentValues.put(DatabaseSchema.ScheduledActionEntry.COLUMN_LAST_RUN,
                    scheduledAction.getLastRunTime());
            contentValues.put(DatabaseSchema.ScheduledActionEntry.COLUMN_EXECUTION_COUNT,
                    scheduledAction.getExecutionCount());
            db.update(DatabaseSchema.ScheduledActionEntry.TABLE_NAME, contentValues,
                    DatabaseSchema.ScheduledActionEntry.COLUMN_UID + "=?", new String[]{scheduledAction.getUID()});
        }
    }

    /**
     * Executes scheduled backups for a given scheduled action.
     * The backup will be executed only once, even if multiple schedules were missed
     * @param scheduledAction Scheduled action referencing the backup
     * @param db SQLiteDatabase to backup
     * @return Number of times backup is executed. This should either be 1 or 0
     */
    private static int executeBackup(ScheduledAction scheduledAction, SQLiteDatabase db) {
        if (!shouldExecuteScheduledBackup(scheduledAction))
            return 0;

        ExportParams params = ExportParams.parseCsv(scheduledAction.getTag());
        // HACK: the tag isn't updated with the new date, so set the correct by hand
        params.setExportStartTime(new Timestamp(scheduledAction.getLastRunTime()));
        try {
            //wait for async task to finish before we proceed (we are holding a wake lock)
            new ExportAsyncTask(GnuCashApplication.getAppContext(), db).execute(params).get();
        } catch (InterruptedException | ExecutionException e) {
            Crashlytics.logException(e);
            Log.e(LOG_TAG, e.getMessage());
        }
        return 1;
    }

    /**
     * Check if a scheduled action is due for execution
     * @param scheduledAction Scheduled action
     * @return {@code true} if execution is due, {@code false} otherwise
     */
    private static boolean shouldExecuteScheduledBackup(ScheduledAction scheduledAction) {
        long now = System.currentTimeMillis();
        long endTime = scheduledAction.getEndTime();

        if (endTime > 0 && endTime < now)
            return false;

        if (scheduledAction.computeNextTimeBasedScheduledExecutionTime() > now)
            return false;

        return true;
    }

    /**
     * Executes scheduled transactions which are to be added to the database.
     * <p>If a schedule was missed, all the intervening transactions will be generated, even if
     * the end time of the transaction was already reached</p>
     * @param scheduledAction Scheduled action which references the transaction
     * @param db SQLiteDatabase where the transactions are to be executed
     * @return Number of transactions created as a result of this action
     */
    private static int executeTransactions(ScheduledAction scheduledAction, SQLiteDatabase db) {
        int executionCount = 0;
        String actionUID = scheduledAction.getActionUID();
        TransactionsDbAdapter transactionsDbAdapter = new TransactionsDbAdapter(db, new SplitsDbAdapter(db));
        Transaction trxnTemplate = null;
        try {
            trxnTemplate = transactionsDbAdapter.getRecord(actionUID);
        } catch (IllegalArgumentException ex){ //if the record could not be found, abort
            Log.e(LOG_TAG, "Scheduled transaction with UID " + actionUID + " could not be found in the db with path " + db.getPath());
            return executionCount;
        }


        long now = System.currentTimeMillis();
        //if there is an end time in the past, we execute all schedules up to the end time.
        //if the end time is in the future, we execute all schedules until now (current time)
        //if there is no end time, we execute all schedules until now
        long endTime = scheduledAction.getEndTime() > 0 ? Math.min(scheduledAction.getEndTime(), now) : now;
        int totalPlannedExecutions = scheduledAction.getTotalPlannedExecutionCount();
        List<Transaction> transactions = new ArrayList<>();

        int previousExecutionCount = scheduledAction.getExecutionCount(); // We'll modify it
        //we may be executing scheduled action significantly after scheduled time (depending on when Android fires the alarm)
        //so compute the actual transaction time from pre-known values
        long transactionTime = scheduledAction.computeNextCountBasedScheduledExecutionTime();
        while (transactionTime <= endTime) {
            Transaction recurringTrxn = new Transaction(trxnTemplate, true);
            recurringTrxn.setTime(transactionTime);
            transactions.add(recurringTrxn);
            recurringTrxn.setScheduledActionUID(scheduledAction.getUID());
            scheduledAction.setExecutionCount(++executionCount); //required for computingNextScheduledExecutionTime

            if (totalPlannedExecutions > 0 && executionCount >= totalPlannedExecutions)
                break; //if we hit the total planned executions set, then abort
            transactionTime = scheduledAction.computeNextCountBasedScheduledExecutionTime();
        }

        transactionsDbAdapter.bulkAddRecords(transactions, DatabaseAdapter.UpdateMethod.insert);
        // Be nice and restore the parameter's original state to avoid confusing the callers
        scheduledAction.setExecutionCount(previousExecutionCount);
        return executionCount;
    }

    /**
     * Perform an automatic backup of all books in the database.
     * This method is run everytime the service is executed
     */
    private static void autoBackup(){
        BooksDbAdapter booksDbAdapter = BooksDbAdapter.getInstance();
        List<String> bookUIDs = booksDbAdapter.getAllBookUIDs();
        Context context = GnuCashApplication.getAppContext();

        for (String bookUID : bookUIDs) {
            String backupFile = BookUtils.getBookBackupFileUri(bookUID);
            if (backupFile == null){
                GncXmlExporter.createBackup();
                continue;
            }

            try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(context.getContentResolver().openOutputStream(Uri.parse(backupFile)))){
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(bufferedOutputStream);
                OutputStreamWriter writer = new OutputStreamWriter(gzipOutputStream);
                ExportParams params = new ExportParams(ExportFormat.XML);
                new GncXmlExporter(params).generateExport(writer);
                writer.close();
            } catch (IOException ex) {
                Log.e(LOG_TAG, "Auto backup failed for book " + bookUID);
                ex.printStackTrace();
                Crashlytics.logException(ex);
            }
        }
    }
}
