package com.tutosandroidfrance.wearsample;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import retrofit.Callback;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class WearService extends WearableListenerService {

    private final static String TAG = WearService.class.getCanonicalName();

    protected GoogleApiClient mApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mApiClient.connect();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mApiClient.disconnect();
    }

    /**
     * Appellé à la réception d'un message envoyé depuis la montre
     *
     * @param messageEvent message reçu
     */
    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        //Ouvre une connexion vers la montre
        ConnectionResult connectionResult = mApiClient.blockingConnect(30, TimeUnit.SECONDS);

        if (!connectionResult.isSuccess()) {
            Log.e(TAG, "Failed to connect to GoogleApiClient.");
            return;
        }

        //traite le message reçu
        final String path = messageEvent.getPath();

        if (path.equals("bonjour")) {

            //Utilise Retrofit pour réaliser un appel REST
            AndroidService androidService = new RestAdapter.Builder()
                    .setEndpoint(AndroidService.ENDPOINT)
                    .build().create(AndroidService.class);

            //Récupère et deserialise le contenu de mon fichier JSON en objet Element
            androidService.getElements(new Callback<List<Element>>() {
                @Override
                public void success(List<Element> elements, Response response) {
                    envoyerListElements(elements);
                }

                @Override
                public void failure(RetrofitError error) {
                }
            });

        }
    }

    /**
     * Envoie la liste d'éléments à la montre
     * Envoie de même les images
     * @param elements
     */
    private void envoyerListElements(final List<Element> elements) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int nombreElements = elements.size();

                //Envoie des elements et leurs images
                sendElements(elements);

                //puis indique à la montre le nombre d'éléments à afficher
                sendMessage("nombre_elements",String.valueOf(nombreElements));
            }
        }).start();
    }

    /**
     * Envoie un message à la montre
     *
     * @param path    identifiant du message
     * @param message message à transmettre
     */
    protected void sendMessage(final String path, final String message) {
        //effectué dans un trhead afin de ne pas être bloquant
        new Thread(new Runnable() {
            @Override
            public void run() {
                //envoie le message à tous les noeuds/montres connectées
                final NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes(mApiClient).await();
                for (Node node : nodes.getNodes()) {
                    Wearable.MessageApi.sendMessage(mApiClient, node.getId(), path, message.getBytes()).await();

                }
            }
        }).start();
    }

    /**
     * Récupère une bitmap à partir d'une URL
     */
    public static Bitmap getBitmapFromURL(String src) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(src).openConnection();
            connection.setDoInput(true);
            connection.connect();
            return BitmapFactory.decodeStream(connection.getInputStream());
        } catch (Exception e) {
            // Log exception
            return null;
        }
    }

    /**
     * Les bitmap transferés depuis les DataApi doivent être empaquetées en Asset
     */
    public static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }

    /**
     * Permet d'envoyer une liste d'elements
     */
    protected void sendElements(final List<Element> elements) {

        //envoie chaque élémént 1 par 1
        for (int position = 0; position < elements.size(); ++position) {

            Element element = elements.get(position);

            //créé un emplacement mémoire "element/[position]"
            final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/element/" + position);

            //ajoute la date de mi[jase à jour
            putDataMapRequest.getDataMap().putString("timestamp", new Date().toString());

            //ajoute l'element champ par champ
            putDataMapRequest.getDataMap().putString("titre", element.getTitre());
            putDataMapRequest.getDataMap().putString("description", element.getDescription());
            putDataMapRequest.getDataMap().putString("url", element.getUrl());

            //envoie la donnée à la montre
            if (mApiClient.isConnected())
                Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());

            //puis envoie l'image associée
            sendImage(element.getUrl(), position);
        }
    }

    /**
     * Permet d'envoyer une image à la montre
     */
    protected void sendImage(String url, int position) {
        //télécharge l'image
        Bitmap bitmap = getBitmapFromURL(url);
        if (bitmap != null) {
            Asset asset = createAssetFromBitmap(bitmap);

            //créé un emplacement mémoire "image/[url_image]"
            final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/image/" + position);

            //ajoute la date de mise à jour, important pour que les données soient mises à jour
            putDataMapRequest.getDataMap().putString("timestamp", new Date().toString());

            //ajoute l'image à la requête
            putDataMapRequest.getDataMap().putAsset("image", asset);

            //envoie la donnée à la montre
            if (mApiClient.isConnected())
                Wearable.DataApi.putDataItem(mApiClient, putDataMapRequest.asPutDataRequest());
        }
    }

}
