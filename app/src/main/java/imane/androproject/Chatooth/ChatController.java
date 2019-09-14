package imane.androproject.Chatooth;



import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class ChatController {
    private static final String APP_NAME = "BluetoothChatApp";
    private static final UUID MY_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    private final BluetoothAdapter bluetoothAdapter;
    private final Handler handler;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ReadWriteThread connectedThread;
    private int state;

    static final int STATE_NONE = 0;
    static final int STATE_LISTEN = 1;
    static final int STATE_CONNECTING = 2;
    static final int STATE_CONNECTED = 3;

    public ChatController(Context context, Handler handler) {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        state = STATE_NONE;

        this.handler = handler;
    }

    // "setters getters" de l'etat courante de la connexion
    private synchronized void setState(int state) {
        this.state = state;

        handler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    public synchronized int getState() {
        return state;
    }

    // commencer le service
    public synchronized void start() {
        // fermer ts les threads
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // fermer ts les thresds entrain d'execution
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(STATE_LISTEN);
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
    }

    // initialisation de la connexion
    public synchronized void connect(BluetoothDevice device) {

        if (state == STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread.cancel();
                connectThread = null;
            }
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        //execution du thread de la connexion avec l apparei donne
        connectThread = new ConnectThread(device);
        connectThread.start();
        setState(STATE_CONNECTING);
    }

    // manage Bluetooth connection
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }


        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        // execution du thread permettant la gestion de la transmission
        connectedThread = new ReadWriteThread(socket);
        connectedThread.start();

        Message msg = handler.obtainMessage(MainActivity.MESSAGE_DEVICE_OBJECT);
        Bundle bundle = new Bundle();
        bundle.putParcelable(MainActivity.DEVICE_OBJECT, device);
        msg.setData(bundle);
        handler.sendMessage(msg);

        setState(STATE_CONNECTED);
    }


    public synchronized void stop() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        setState(STATE_NONE);
    }

    public void write(byte[] out) {
        ReadWriteThread r;
        synchronized (this) {
            if (state != STATE_CONNECTED)
                return;
            r = connectedThread;
        }
        r.write(out);
    }

    private void connectionFailed() {
        Message msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "Unable to connect device");
        msg.setData(bundle);
        handler.sendMessage(msg);


        ChatController.this.start();
    }

    private void connectionLost() {
        Message msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString("toast", "Device connection was lost");
        msg.setData(bundle);
        handler.sendMessage(msg);


        ChatController.this.start();
    }


    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(APP_NAME, MY_UUID);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            serverSocket = tmp;
        }

        public void run() {
            setName("AcceptThread");
            BluetoothSocket socket;
            while (state != STATE_CONNECTED) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    break;
                }

                // si une connexion est acceptee..
                if (socket != null) {
                    synchronized (ChatController.this) {
                        switch (state) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                // le thread connecte commence..
                                connected(socket, socket.getRemoteDevice());
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:

                                try {
                                    socket.close();
                                } catch (IOException e) {
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                serverSocket.close();
            } catch (IOException e) {
            }
        }
    }




    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                e.printStackTrace();
            }
            socket = tmp;
        }

        public void run() {
            setName("ConnectThread");

            // on ferme tjrs "devices dyscovery" pr ne pas alourdir la connexion
            bluetoothAdapter.cancelDiscovery();

            // creation d'une connexion au bt socket
            try {
                socket.connect();
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException e2) {
                }
                connectionFailed();
                return;
            }

            // on supprime la connexion du thread
            synchronized (ChatController.this) {
                connectThread = null;
            }


            connected(socket, device);
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
            }
        }
    }


    //gestion des ttransmissions des msg
    private class ReadWriteThread extends Thread {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public ReadWriteThread(BluetoothSocket socket) {
            this.bluetoothSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }

            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            // while inputstream...
            while (true) {
                try {
                    // lecture..
                    bytes = inputStream.read(buffer);

                    // envoie..
                    handler.obtainMessage(MainActivity.MESSAGE_READ, bytes, -1,
                            buffer).sendToTarget();
                } catch (IOException e) {
                    connectionLost();

                    ChatController.this.start();
                    break;
                }
            }
        }

        // ecriture
        public void write(byte[] buffer) {
            try {
                outputStream.write(buffer);
                handler.obtainMessage(MainActivity.MESSAGE_WRITE, -1, -1,
                        buffer).sendToTarget();
            } catch (IOException e) {
            }
        }

        public void cancel() {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
