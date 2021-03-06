package chat.rocket.android.content.observer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import bolts.Continuation;
import bolts.Task;
import chat.rocket.android.api.ws.RocketChatWSAPI;
import chat.rocket.android.content.RocketChatProvider;
import chat.rocket.android.model.Message;
import chat.rocket.android.model.SyncState;
import chat.rocket.android_ddp.DDPClientCallback;

public class SendNewMessageHandler extends AbstractObserver {

    public SendNewMessageHandler(Context context, Looper looper, RocketChatWSAPI api) {
        super(context, looper, api);
    }

    @Override
    public Uri getTargetUri() {
        return RocketChatProvider.getUriForQuery(Message.TABLE_NAME);
    }

    @Override
    protected void onCreate(Uri uri) {
        super.onCreate(uri);
        Cursor c = mContext.getContentResolver().query(uri,null,"syncstate!=2 AND id IS NULL",null,null);
        if(c==null) return;
        while(c.moveToNext()) handleNewMessage(c);
        c.close();
    }

    @Override
    protected void onChange(Uri uri) {
        Cursor c = mContext.getContentResolver().query(uri,null,"syncstate=0 AND id IS NULL",null,null);
        if(c==null) return;
        if (c.getCount()>0 && c.moveToFirst()) handleNewMessage(c);
        c.close();
    }

    private void handleNewMessage(Cursor c) {
        final Message m = Message.createFromCursor(c);

        m.syncstate = SyncState.SYNCING;
        m.putByContentProvider(mContext);

        try {
            JSONObject params = new JSONObject();
            JSONObject extras = TextUtils.isEmpty(m.extras)? new JSONObject() : new JSONObject(m.extras);

            if(!extras.isNull("file")) params.put("file", extras.getJSONObject("file"));
            if(!m.isGroupable()) params.put("groupable", false);
            if(!TextUtils.isEmpty(m.attachments)) params.put("attachments", new JSONArray(m.attachments));

            mAPI.sendMessage(m.roomId, m.content, params).continueWith(new Continuation<DDPClientCallback.RPC, Object>() {
                @Override
                public Object then(Task<DDPClientCallback.RPC> task) throws Exception {
                    JSONObject result = task.getResult().result;
                    if(task.isFaulted()){
                        m.syncstate = SyncState.FAILED;
                        Log.e(TAG,"error",task.getError());
                    }
                    else{
                        m.syncstate = SyncState.SYNCED;
                        m.id = result.getString("_id");
                        m.timestamp = result.getJSONObject("ts").getLong("$date");
                        m.userId = result.getJSONObject("u").getString("username");
                    }
                    m.putByContentProvider(mContext);
                    return null;
                }
            });
        } catch (JSONException e) {
            Log.e(TAG,"error",e);
        }
    }
}
