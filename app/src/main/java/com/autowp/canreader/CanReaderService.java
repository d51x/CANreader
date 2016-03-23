package com.autowp.canreader;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import com.autowp.can.CanAdapter;
import com.autowp.can.CanBusSpecs;
import com.autowp.can.CanClient;
import com.autowp.can.CanClientException;
import com.autowp.can.CanFrame;
import com.autowp.can.CanFrameException;
import com.autowp.can.CanMessage;
import com.autowp.can.adapter.android.CanHackerFelhr;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CanReaderService extends Service {

    public interface OnConnectedStateChangeListener {
        void handleConnectedStateChanged(CanClient.ConnectionState connection);
    }

    private final List<OnConnectedStateChangeListener> connectedStateChangeListeners = new ArrayList<>();

    private ArrayList<TransmitCanFrame> transmitFrames = new ArrayList<>();

    private ArrayList<MonitorCanMessage> monitorFrames = new ArrayList<>();

    private final List<OnStateChangeListener> stateChangeListeners = new ArrayList<>();

    private final List<OnTransmitChangeListener> transmitListeners = new ArrayList<>();

    private final List<OnMonitorChangeListener> monitorListeners = new ArrayList<>();

    private final CanBusSpecs canBusSpecs;

    private final CanClient canClient;

    private int sentCount = 0;

    private int receivedCount = 0;

    private ScheduledExecutorService threadsPool = Executors.newScheduledThreadPool(1);

    private static final int SPEED_METER_PERIOD = 500;
    private SpeedMeterTimerTask mSpeedMeterTimerTask;

    private class UsbBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    CanAdapter adapter = canClient.getCanAdapter();
                    if (adapter instanceof CanHackerFelhr) {
                        UsbDevice adapterDevice = ((CanHackerFelhr)adapter).getUsbDevice();
                        if (device.equals(adapterDevice)) {
                            setCanAdapter(null);
                        }
                    }
                }
            }

        }
    }

    private UsbBroadcastReceiver mUsbReceiver;

    private class TransmitRunnable implements Runnable {

        private TransmitCanFrame frame;

        public TransmitRunnable(TransmitCanFrame frame) {
            this.frame = frame;
        }

        @Override
        public void run() {
            if (frame.isEnabled()) {
                transmit(frame);
            }
        }
    }

    private class SpeedMeterTimerTask extends TimerTask {
        private int previousSentCount = 0;
        private int previousReceivedCount = 0;

        public SpeedMeterTimerTask() {
            this.previousSentCount = sentCount;
        }

        public void run() {
            double seconds = (double)SPEED_METER_PERIOD / 1000.0;

            double dxSent = sentCount - previousSentCount;
            triggerTransmitSpeed(dxSent / seconds);
            this.previousSentCount = sentCount;

            double dxReceived = receivedCount - previousReceivedCount;
            triggerMonitorSpeed(dxReceived / seconds);
            this.previousReceivedCount = receivedCount;
        }
    }

    private void triggerTransmitSpeed(double speed) {
        synchronized (stateChangeListeners) {
            for (OnTransmitChangeListener listener : transmitListeners) {
                listener.handleSpeedChanged(speed);
            }
        }
    }

    private void triggerMonitorSpeed(double speed) {
        synchronized (stateChangeListeners) {
            for (OnMonitorChangeListener listener : monitorListeners) {
                listener.handleSpeedChanged(speed);
            }
        }
    }

    public interface OnStateChangeListener {
        void handleStateChanged();
    }

    public interface OnTransmitChangeListener {
        void handleTransmitUpdated();
        void handleTransmitUpdated(final TransmitCanFrame frame);
        void handleSpeedChanged(double speed);
    }

    public interface OnMonitorChangeListener {
        void handleMonitorUpdated();

        void handleMonitorUpdated(final MonitorCanMessage message);

        void handleSpeedChanged(double speed);
    }

    private void toast(final String message)
    {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast toast = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT);
                toast.show();
            }
        });
    }

    @Override
    public void onDestroy()
    {
        if (mUsbReceiver != null) {
            unregisterReceiver(mUsbReceiver);
            mUsbReceiver = null;
        }
    }

    public void setCanAdapter(final CanAdapter adapter) {
        stopAllTransmits();

        if (mSpeedMeterTimerTask != null) {
            mSpeedMeterTimerTask.cancel();
            mSpeedMeterTimerTask = null;
        }

        if (mUsbReceiver != null) {
            unregisterReceiver(mUsbReceiver);
            mUsbReceiver = null;
        }

        try {
            canClient.disconnect(new Runnable() {
                @Override
                public void run() {
                    try {
                        canClient.setAdapter(adapter);

                        if (adapter != null) {

                            try {
                                canClient.connect(null);

                                mUsbReceiver = new UsbBroadcastReceiver();
                                IntentFilter filter = new IntentFilter();
                                filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                                registerReceiver(mUsbReceiver, filter);

                            } catch (CanClientException e) {
                                e.printStackTrace();

                                toast(e.getMessage());
                            }
                        }

                    } catch (CanClientException e) {
                        e.printStackTrace();

                        toast(e.getMessage());
                    }
                }
            });

        } catch (CanClientException e) {
            e.printStackTrace();

            toast(e.getMessage());
        }


        //triggerStateChanged();
    }

    public void addListener(OnStateChangeListener listener) {

        synchronized (stateChangeListeners) {
            stateChangeListeners.add(listener);
        }
    }

    public void removeListener(OnStateChangeListener listener) {

        synchronized (stateChangeListeners) {
            stateChangeListeners.remove(listener);
        }
    }

    public void addListener(OnConnectedStateChangeListener listener) {

        synchronized (connectedStateChangeListeners) {
            connectedStateChangeListeners.add(listener);
        }
    }

    public void removeListener(OnConnectedStateChangeListener listener) {

        synchronized (connectedStateChangeListeners) {
            connectedStateChangeListeners.remove(listener);
        }
    }

    public void addListener(OnMonitorChangeListener listener) {

        synchronized (monitorListeners) {
            monitorListeners.add(listener);
        }
    }

    public void removeListener(OnMonitorChangeListener listener) {

        synchronized (monitorListeners) {
            monitorListeners.remove(listener);
        }
    }

    public void addListener(OnTransmitChangeListener listener) {

        synchronized (transmitListeners) {
            transmitListeners.add(listener);
        }
    }

    public void removeListener(OnTransmitChangeListener listener) {

        synchronized (transmitListeners) {
            transmitListeners.remove(listener);
        }
    }

    TransferServiceBinder binder = new TransferServiceBinder();

    Timer timer = new Timer();

    /*private class TransmitTimerTask extends TimerTask {
        private TransmitCanFrame frame;
        public TransmitTimerTask(TransmitCanFrame frame) {
            this.frame = frame;
        }

        public void run() {
            transmit(frame);
        }
    }*/

    public CanReaderService() {
        canBusSpecs = new CanBusSpecs();
        canClient = new CanClient(canBusSpecs);

        canClient.addEventListener(new CanClient.OnCanClientErrorListener() {
            @Override
            public void handleErrorEvent(final CanClientException e) {
                Handler h = new Handler(CanReaderService.this.getMainLooper());

                h.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(CanReaderService.this, e.getMessage(),Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        canClient.addEventListener(new CanClient.OnCanMessageTransferListener() {
            @Override
            public void handleCanMessageReceivedEvent(CanMessage message) {
                receive(message);
            }

            @Override
            public void handleCanMessageSentEvent(CanMessage message) {

            }
        });

        canClient.addEventListener(new CanClient.OnClientConnectedStateChangeListener() {
            @Override
            public void handleClientConnectedStateChanged(CanClient.ConnectionState connection) {

                switch (connection) {
                    case DISCONNECTED:
                        break;
                    case CONNECTING:
                        break;
                    case CONNECTED:
                        if (mSpeedMeterTimerTask == null) {
                            mSpeedMeterTimerTask = new SpeedMeterTimerTask();
                            timer.schedule(mSpeedMeterTimerTask, 0, SPEED_METER_PERIOD);

                            /*DEBUG*/
                            /*try {
                                CanFrame f = new CanFrame(0x0F6, new byte[] {(byte)0x8E, (byte)0x87, 0x32, (byte)0xFA, 0x26, (byte)0x8E, (byte)0xBE, (byte)0x86}, false);
                                TransmitCanFrame tf = new TransmitCanFrame(f, 500);
                                transmitFrames.add(tf);
                                CanFrame f2 = new CanFrame(0x036, new byte[] {(byte)0x0E, 0x00, 0x00, 0x08, 0x01, 0x00, 0x00, (byte)0xA0}, false);
                                TransmitCanFrame tf2 = new TransmitCanFrame(f2, 100);
                                transmitFrames.add(tf2);
                            } catch (CanFrameException e) {
                                e.printStackTrace();
                            }*/

                        }
                        break;
                    case DISCONNECTING:
                        break;
                }

                trigerConnectionStateChanged();
            }
        });
    }

    public void transmit(TransmitCanFrame frame)
    {
        send(frame.getCanFrame());
        frame.incCount();
        sentCount++;
        triggerTransmit(frame);
    }

    private void triggerStateChanged()
    {
        synchronized (stateChangeListeners) {
            for (OnStateChangeListener listener : stateChangeListeners) {
                listener.handleStateChanged();
            }
        }
    }

    private void trigerConnectionStateChanged()
    {
        synchronized (connectedStateChangeListeners) {
            CanClient.ConnectionState state = canClient.getConnectionState();
            for (OnConnectedStateChangeListener listener : connectedStateChangeListeners) {
                listener.handleConnectedStateChanged(state);
            }
        }
    }

    private void triggerMonitor()
    {
        synchronized (transmitListeners) {
            for (OnMonitorChangeListener listener : monitorListeners) {
                listener.handleMonitorUpdated();
            }
        }
    }

    private void triggerTransmit()
    {
        synchronized (transmitListeners) {
            for (OnTransmitChangeListener listener : transmitListeners) {
                listener.handleTransmitUpdated();
            }
        }
    }

    private void triggerTransmit(TransmitCanFrame frame) {
        synchronized (transmitListeners) {
            for (OnTransmitChangeListener listener : transmitListeners) {
                listener.handleTransmitUpdated(frame);
            }
        }
    }

    public IBinder onBind(Intent intent) {
        return binder;
    }

    class TransferServiceBinder extends Binder {
        CanReaderService getService() {
            return CanReaderService.this;
        }
    }

    public ArrayList<TransmitCanFrame> getTransmitFrames()
    {
        return transmitFrames;
    }

    public ArrayList<MonitorCanMessage> getMonitorFrames()
    {
        return monitorFrames;
    }

    public void add(final TransmitCanFrame frame)
    {
        transmitFrames.add(frame);
        triggerTransmit();
    }

    public void remove(int position)
    {
        TransmitCanFrame frame = transmitFrames.get(position);
        remove(frame);
    }

    public void remove(TransmitCanFrame frame)
    {
        TimerTask tt = frame.getTimerTask();
        if (tt != null) {
            tt.cancel();
            frame.setTimerTask(null);
        }
        transmitFrames.remove(frame);
        triggerTransmit();
    }

    private void receive(CanMessage canMessage)
    {
        receivedCount++;
        boolean found = false;
        for (MonitorCanMessage monitorFrame : monitorFrames) {
            if (monitorFrame.getCanMessage().getId() == canMessage.getId()) {
                monitorFrame.setCanMessage(canMessage);
                monitorFrame.incCount();
                monitorFrame.setTime(new Date());
                found = true;
                break;
            }
        }
        if (!found) {
            MonitorCanMessage monitorFrame = new MonitorCanMessage(canMessage, 0);
            monitorFrame.incCount();
            monitorFrames.add(monitorFrame);
        }
        triggerMonitor();
    }

    private void send(CanFrame frame)
    {
        try {
            canClient.send(frame);
        } catch (CanClientException e) {
            e.printStackTrace();
        }
        //receive(frame); // TODO: loopback stub
    }

    public void startTransmit(TransmitCanFrame frame)
    {
        TimerTask tTask = frame.getTimerTask();
        if (tTask == null) {
            if (frame.getPeriod() > 0) {
                TransmitRunnable runnable = new TransmitRunnable(frame);
                Future<?> future = threadsPool.scheduleWithFixedDelay(runnable, 0, frame.getPeriod(), TimeUnit.MILLISECONDS);

                frame.setFuture(future);
                frame.setEnabled(true);
                triggerTransmit(frame);
            }
        }
    }

    public void startAllTransmits()
    {
        for (TransmitCanFrame frame : transmitFrames) {
            frame.setEnabled(true);
            startTransmit(frame);
        }
    }

    public void stopTransmit(TransmitCanFrame frame)
    {
        Future<?> future = frame.getFuture();
        //TimerTask tt = frame.getTimerTask();
        if (future != null) {
            future.cancel(true);
            //future.cancel(false);
            frame.setFuture(null);
        }
        if (frame.isEnabled()) {
            frame.setEnabled(false);
            triggerTransmit(frame);
        }
    }

    public void stopAllTransmits()
    {
        for (TransmitCanFrame frame : transmitFrames) {
            stopTransmit(frame);
        }
    }

    public void clearTransmits()
    {
        stopAllTransmits();
        transmitFrames.clear();
        triggerTransmit();
    }

    public void clearMonitor()
    {
        monitorFrames.clear();
        triggerMonitor();
    }

    public CanClient.ConnectionState getConnectionState()
    {
        return canClient.getConnectionState();
    }

    public void setSpeed(int speed) {
        canBusSpecs.setSpeed(speed);
    }

    public void resetTransmit(final TransmitCanFrame frame) {
        frame.resetCount();
        triggerTransmit(frame);
    }

    public void resetTransmits() {
        for (TransmitCanFrame frame : transmitFrames) {
            frame.resetCount();
            triggerTransmit(frame);
        }
    }

    public boolean hasStartedTransmits()
    {
        for (TransmitCanFrame frame : transmitFrames) {
            if (frame.isEnabled()) {
                return true;
            }
        }

        return false;
    }

    public boolean hasStoppedTransmits()
    {
        for (TransmitCanFrame frame : transmitFrames) {
            if (!frame.isEnabled()) {
                return true;
            }
        }

        return false;
    }

    public void setTransmitFrames(List<TransmitCanFrame> list) {
        clearTransmits();
        for (TransmitCanFrame frame : list) {
            add(frame);
        }
    }

}
