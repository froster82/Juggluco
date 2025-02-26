/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Thu Mar 28 20:14:36 CET 2024                                                 */


package tk.glucodata;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static java.util.Objects.nonNull;
import static tk.glucodata.Applic.app;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MyGattCallback.showCharacter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import 	android.bluetooth.BluetoothDevice;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;


import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class DexGattCallback extends SuperGattCallback {

    public DexGattCallback(String SerialNumber, long dataptr) {
        super(SerialNumber, dataptr, 0x40);
        //deviceName=Natives.dexGetDeviceName(dataptr);
        Log.d(LOG_ID, SerialNumber + " DexGattCallback(..)");
    }

    static private byte[] lencode(int which, int code) {
        byte[] output = new byte[6];
        output[0] = 0x0b;
        output[1] = (byte) which;
        for (int i = 0; i < 4; i++) {
            output[i + 2] = (byte) (0xFF & (code >> (i * 8)));
        }
        return output;
    }
    private int phase = -1;
    private static final int RoundStart = 0;
    private static final int Round1 = RoundStart + 1;
    private static final int Round2 = Round1 + 1;
    private static final int Round3 = Round2 + 1;
    private static final int RequestAuth = Round3 + 1;
    private static final int ChallengeReply = RequestAuth + 1;
    private static final int SendCertificate0 = ChallengeReply + 1;
    private static final int SendCertificate1 = SendCertificate0 + 1;
    private static final int SendCertificate2 = SendCertificate1 + 1;
    private static final int SendKeyChallenge = SendCertificate2 + 1;
    private static final int SendKeyChallengeOut = SendKeyChallenge + 1;
    private static final int GetData = SendKeyChallengeOut + 1;
    private static final int GetData2 = GetData + 1;


    @SuppressLint("MissingPermission")
    @Override // android.bluetooth.BluetoothGattCallback
    public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int status) {
        super.onDescriptorWrite(bluetoothGatt, bluetoothGattDescriptor, status);
        BluetoothGattCharacteristic characteristic = bluetoothGattDescriptor.getCharacteristic();
        if (doLog) {
            byte[] value = bluetoothGattDescriptor.getValue();
            Log.showbytes("onDescriptorWrite char: " + characteristic.getUuid().toString() + " desc: " + bluetoothGattDescriptor.getUuid().toString() + " status=" + status, value);
        }
       if(characteristic.equals(charact[2])) {
         if(status == BluetoothGatt.GATT_SUCCESS) 
            backfilled = true;
            askbackfill();
            return;
            }
        if(status == BluetoothGatt.GATT_SUCCESS) {
            if (characteristic.equals(charact[3])) {
                has_service = true;
               //  enableNotification(bluetoothGatt, charact[3]); 
                enableIndication(bluetoothGatt, charact[1]);
                return;
            } 
         if(characteristic.equals(charact[0])) {
               final byte[] com={0x4E}; ///Ask for glucose
               charact[0].setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
               write(0,com);  
               return;
               }
         if(phase <RequestAuth) {
             Log.i(LOG_ID, "Last id");
             if(bonded&&Natives.isAuthenticated(dataptr)) {
                 phase = RequestAuth;
                 requestAuth();
             } else {
                 certinbufiter = 0;
                 certinbuf=new byte[160];
                 phase = Round1;
                 cmd(bluetoothGatt,0x0);
                 //Applic.scheduler.schedule(() -> { cmd(bluetoothGatt, 0x0); }, 500, TimeUnit.MILLISECONDS);
//                             postDelayed(()->cmd(0x0),200);
             }
             return;
            }
        } else {
            byte[] value = bluetoothGattDescriptor.getValue();
            var mess = "onDescriptorWrite failed "+value[0];
            handshake = mess;
            wrotepass[1] = System.currentTimeMillis();
            Log.e(LOG_ID, mess);
            enableGattDescriptor(bluetoothGatt,  characteristic ,value);
             }
//            disconnect();
    }
  private boolean bonded=false;

//private long connectedtime=0L;
private boolean connected=false;
    @SuppressLint("MissingPermission")
    @Override
    public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
        if (stop) {
            Log.i(LOG_ID, "onConnectionStateChange stop==true");
            return;
        }
        long tim = System.currentTimeMillis();
        final var bondstate = bluetoothGatt.getDevice().getBondState();
        if (doLog) {
				final String[] state = {"DISCONNECTED", "CONNECTING", "CONNECTED", "DISCONNECTING"};
				Log.i(LOG_ID, SerialNumber + " onConnectionStateChange, status:" + status + ", state: " + (newState < state.length ? state[newState] : newState) + " bondstate=" + bondstate);

        }
        if (bondstate == BluetoothDevice.BOND_BONDING) {
            Log.i(LOG_ID, "wait BOND_BONDING");
        } else {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
               connected=true;
                if(bondstate == BOND_NONE || bondstate == BOND_BONDED) {
                  constatchange[0] = tim;
                  if((tim-datatime)<60000) {
                     return;
                     }
                  bonded=bondstate== BOND_BONDED;
                 if(!bluetoothGatt.discoverServices()) {
                     final String mess="bluetoothGatt.discoverServices()  failed";
                     Log.e(LOG_ID, mess);
                     handshake = mess;
                     wrotepass[1] = tim;
                     disconnect();
                    }    

                } else {
                    Log.e(LOG_ID, "getBondState() returns unknown state " + bondstate);
                    disconnect();
                }
            } else {
                connected=false;
                constatstatus = status;
                constatchange[1] = tim;
                if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                  if(datatime==0L)  {
                     if(misconnect>15)  {
                        misconnect=0;
                        mActiveDeviceAddress=null;
                        }
                     else
                        ++misconnect;
                     }
                  close();
                  if(!stop) {
                      var sensorbluetooth = SensorBluetooth.blueone;
                      if (sensorbluetooth != null) {
                         long alreadywaited=tim-constatchange[0]; 
                         long stillwait=justdata?(7000-alreadywaited):0;
                         Log.i(LOG_ID,"justdata="+justdata+" alreadywaited="+alreadywaited+" stillwait="+stillwait);
                         sensorbluetooth.connectToActiveDevice(this,stillwait);
                         }
                     }
                 }          
            }
        }
      justdata=false;
    }


    static private final UUID serviceUUID = UUID.fromString("f8083532-849e-531c-c594-30f1f86a4ea5");

    static private final UUID[] charuuid = {UUID.fromString("f8083534-849e-531c-c594-30f1f86a4ea5"),

            UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5"),

            UUID.fromString("f8083536-849e-531c-c594-30f1f86a4ea5"),

            UUID.fromString("f8083538-849e-531c-c594-30f1f86a4ea5")};


    private final BluetoothGattCharacteristic[] charact = new BluetoothGattCharacteristic[charuuid.length];
    private boolean has_service = false;

    private boolean discover(BluetoothGatt bluetoothGatt) {
        Log.i(LOG_ID, "discover");
        BluetoothGattService service = bluetoothGatt.getService(serviceUUID);
        if (service == null) {
            var mess = "getService(serviceUUID)==null";
            Log.i(LOG_ID, mess);
            handshake = mess;
            wrotepass[1] = System.currentTimeMillis();
            return false;
        }
        final var len = charuuid.length;
        for (int i = 0; i < len; i++) {
            var uuid = charuuid[i];
            charact[i] = service.getCharacteristic(uuid);
            if (charact[i] == null) {
                var mess = "getCharacteristic(" + uuid + ")==null";
                Log.i(LOG_ID, mess);
                wrotepass[1] = System.currentTimeMillis();
                return false;
            } else {
                Log.i(LOG_ID, i + ": getCharacteristic(" + uuid + ")");
            }
           }
         enableNotification(bluetoothGatt, charact[3]);
//       Applic.scheduler.schedule(() -> { enableNotification(bluetoothGatt, charact[3]); }, 200, TimeUnit.MILLISECONDS);
        return true;
    }


    @Override // android.bluetooth.BluetoothGattCallback
    public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
        Log.i(LOG_ID, "BLE onServicesDiscovered invoked, status: " + status);
        if (status == GATT_SUCCESS) {
                if(!discover(bluetoothGatt)) 
                  disconnect();
                return;
        }

        disconnect();
    }


private void sendcerts() {
    Applic.scheduler.schedule(this::sendcertthread, 0, TimeUnit.MILLISECONDS);
   }

private static void sleep(long mmsec) {
   try {
       Thread.sleep(mmsec);
   } catch (InterruptedException ie) {
       Thread.currentThread().interrupt();
   }
}
//private String deviceName;
private int misconnect=0;
private boolean newcertificates=false;
private void sendcertthread() {
   for(startpacket=0; startpacket <packet.length;) {
        sleep(40);
        var oldstart = startpacket;
        startpacket += 20;
        if(startpacket > packet.length) startpacket = packet.length;
        Log.i(LOG_ID,"writenext until "+startpacket);
        if(!write(3, Arrays.copyOfRange(packet, oldstart, startpacket))) {
            startpacket = oldstart;
          }
      }
    if(phase >= Round1 && phase < Round3) {
       certinbufiter = 0;
       sleep(40);
       cmd(mBluetoothGatt, phase++);
        return;
    } else {
        switch(++phase) {
           case SendKeyChallengeOut: {
               final byte[] sendchal={0x0d, 0x00, 0x02};
               if(!write(1, sendchal)) {
                  sleep(2);
                  write(1, sendchal);
                  }
               return;
              }
           case RequestAuth: {
               newcertificates=true;
               sleep(40);
               requestAuth();
               return;
            }
          case SendCertificate2: {
             sleep(40);
             askcertificate(SendCertificate2);
             return;
             }
          case  SendKeyChallenge: {
               sleep(40);
                sendkeychallenge();
                return;
                }
          }

    }
      }
    @Override
    public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int status) {
//        Log.d(LOG_ID, bluetoothGatt.getDevice().getAddress() + " onCharacteristicWrite, status:" + status + " UUID:" + bluetoothGattCharacteristic.getUuid().toString());
        showCharacter("onCharacteristicWrite " + bluetoothGatt.getDevice().getAddress() + " status:" + status + " ", bluetoothGattCharacteristic);
    }

    @SuppressWarnings("unused")
    public void onConnectionUpdated(BluetoothGatt gatt, int interval, int latency, int timeout, int status) {
        Log.i(LOG_ID, "onConnectionUpdated interval=" + interval + " latency=" + latency + " timeout=" + timeout + " status=" + status);
    }


    private boolean requestAuth() {
        Log.i(LOG_ID,"requestAuth()");
        Random.fillbytes(random8);
        var uit = new byte[10];
        System.arraycopy(random8, 0, uit, 1, random8.length);
        uit[0] = uit[9] = (byte) 0x02;
        charact[1].setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        return write(1, uit);
      }

private final byte[] random8 = new byte[8];

private    byte[] certinbuf;
private    int certinbufiter = 0;



private    int certsize = 0x10000;

    static private boolean equalpart(byte[] one, byte[] two, int twofrom) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Arrays.equals(one, 0, one.length, two, twofrom, twofrom + one.length);
        } else {
            final int len = one.length;
            if ((two.length - twofrom) < len)
                return false;
            for (int i = 0; i < len; i++) {
                if (one[i] != two[i + twofrom])
                    return false;
            }
            return true;
        }
    }

private boolean askcertificate(int pha) {
      Log.i(LOG_ID,"askcertificate("+pha+")");
         phase = pha;
         final int index= pha-SendCertificate1;
         certinbufiter = 0;
         int len = certs[index].length;
         certsize = len;
         certinbuf = new byte[len];
         byte[] code = lencode(index, len);
         return write(1, code);
         }


 private static final byte[][] bondBytes = {{(byte) 0x06, (byte) 0x19}, 
 {(byte) 0xFF, (byte) 0x06, (byte) 0x01},
            {(byte) 0x06, (byte) 0x00}};

 private void authenticate(byte[] value) {
    final var bondstate = mActiveBluetoothDevice.getBondState();
     if(bondstate!=BOND_BONDED) {
        Log.i(LOG_ID,"authenticate bondstate="+bondstate);
           for(var b : bondBytes) {
               if(Arrays.equals(b, value)) {
                   Log.i(LOG_ID,"createBond");
                   if(mActiveBluetoothDevice.createBond()) {
                        Log.i(LOG_ID,"createBond success");
                   }
                   else
                        Log.i(LOG_ID,"createBond failure");
                   break;
                  }
              }
           }
        switch (phase) {
            case RequestAuth: { 
                byte[] aes = new byte[8];
                Log.showbytes("random8 ", random8);
                boolean aesSu = Natives.dex8AES(dataptr, random8, 0, aes, 0);
                Log.showbytes("dex8AES ", aes);
                Log.showbytes("value ", value);
                boolean verified = equalpart(aes, value, 1);
                Log.i(LOG_ID, "dex8AES(dataptr,random8,aes)=" + aesSu + (verified ? " verified" : " not verified"));
               if(!verified) {
                  handshake = "dex8AES different";
                  wrotepass[1] = System.currentTimeMillis();
                  resetCerts();
                  return;
                  }  
                ++phase; //          state = ChallengeReply;
                byte[] dataaes = new byte[9];
                Natives.dex8AES(dataptr, value, 9, dataaes, 1);
                dataaes[0] = 0x04;
                Log.showbytes("dex8AES data ", dataaes);
                if(!write(1, dataaes)) {
                        Applic.scheduler.schedule(()->{
                           if(connected)
                              write(1,dataaes);
                           }
                        , 20, TimeUnit.MILLISECONDS);
                  }
            }
            ;
            break;
            case ChallengeReply: {
                int auth = 0, bond = 0;
                if(value.length >= 3 && value[0] == (byte) 0x05) {
                    auth = value[1];
                    bond = value[2];
                }
               Log.i(LOG_ID,"authenticate ChallengeReply auth="+auth+" bond="+bond);
                if(bond == 3)   {
                  handshake = "bond==3";
                  wrotepass[1] = System.currentTimeMillis();
                  resetCerts();
                  return;
                  }
                boolean isbonded = bond == 1;

                if(!newcertificates&&auth != 1) {
                    handshake = "auth != 1";
                    wrotepass[1] = System.currentTimeMillis();
                    resetCerts();
                } else {
                    if(auth==1&&isbonded||(bonded&&bond==2)) {
                        getdatacmd();
                    } else {
                        askcertificate(SendCertificate1);
                    }
                }

            }
            ;
            break;
            case SendCertificate1:
            case SendCertificate2: {
               final int from1=phase-SendCertificate1;
               certsize = Natives.getDexCertSize(value);
               Log.i(LOG_ID,"authenticate SendCertificate"+(from1+1)+" size="+certsize);
                if(certsize < 0) {
                    handshake = "certsize < 0";
                    wrotepass[1] = System.currentTimeMillis();
                    resetCerts();
                }   
                }
            ;
            break;
            case SendKeyChallenge: {
                startpacket = 0;
                packet = Natives.dexChallenger(value);
                sendcerts();
            }
            ;
            break;
            case SendKeyChallengeOut: {
               Log.i(LOG_ID,"authenticate SendKeyChallengeOut");
               final var deviceName=mActiveBluetoothDevice.getName();
               Natives.dexSaveDeviceName(dataptr,deviceName);
                phase = GetData2; //GetData?
                write(1, new byte[]{0x06, 0x19});
                break;
            }
            default:  {
               final byte[] connect={(byte) 0x06, (byte) 0x01};
               if(Arrays.equals( value,connect) || (phase==GetData2&&mActiveBluetoothDevice.getBondState()==BOND_BONDED)) {
                  getdatacmd();
                  return;
                  }
               else
                  Log.i(LOG_ID,"authenticate do nothing");
                  }


        }
    }

private    void getdatacmd() {
     wrotepass[0] = System.currentTimeMillis();
     phase = GetData2;
     //   enableIndication(mBluetoothGatt, charact[0]);
     if(!enableNotification(mBluetoothGatt, charact[0])) {
            Applic.scheduler.schedule(() -> { 
               if(connected)
                  enableNotification(mBluetoothGatt, charact[0]);
                  }, 10, TimeUnit.MILLISECONDS);
            }
    }

    void getcert(byte[] value) {
        System.arraycopy(value, 0, certinbuf, certinbufiter, value.length);
        certinbufiter += value.length;
        Log.i(LOG_ID,"getcert +"+value.length+"="+certinbufiter);
        switch (phase) {
            case Round1:
            case Round2:
            case Round3: {
                if (certinbufiter == 160) {
                    int fromround1=phase-Round1;
                    Natives.dexPutPubKey(dataptr, fromround1, certinbuf);
                    if (phase < Round3)
                        sendpacket(fromround1);
                    else {
                        packet = Natives.makeRound3bytes(dataptr);
                        startpacket = 0;
                        sendcerts();
                        return;
                    }
                }
            }
            ;
            break;
            case SendCertificate1:
            case SendCertificate2: {
                if(certinbufiter >= certsize) {
                   Log.i(LOG_ID,"SendCertificate"+(phase-SendCertificate1+1)+" full");
                      startpacket = 0;
                      packet = certs[phase-SendCertificate1];
                      sendcerts();
                    } 
            }
            ;
            break;
            case SendKeyChallenge: {
                if (certinbufiter == 64) {
                    Log.i(LOG_ID, "SendKeyChallenge: received all");
                }
            }
            ;
        }
    }

    private void sendkeychallenge() {
       phase = SendKeyChallenge;
       Log.i(LOG_ID,"sendkeychallenge");
        certinbufiter = 0;
        certinbuf = new byte[64];
        byte[] buf = new byte[17];
        Random.fillbytes(buf);
        buf[0] = 0x0c;
        write(1, buf);
    }
private boolean justdata=false;
private long datatime=0L;
private    void getdata(byte[] value) {
        final long timmsec = System.currentTimeMillis();
        switch (value[0]) {
            case 0x4E: {
                justdata=true;
                long[] timeres={timmsec,0L};
                Natives.dexcomProcessData(dataptr, value, timeres);
                final long res= timeres[1];
                final long newtime= timeres[0];
                final int glumgdl = (int) (res & 0xFFFFFFFFL);
                Log.i(LOG_ID, "dexcomProcessData newtime="+newtime+" res="+res+" "+glumgdl+" mg/dL "+(glumgdl/18.0)+ " mmol/L");                  
                handleGlucoseResult(res, newtime);
                Applic.scheduler.schedule(()->{
                  if(connected) askbackfill();}, 10, TimeUnit.MILLISECONDS);
                datatime=timmsec;
            };break;
           case 0x59:{
              Applic.app.redraw();
              Natives.dexEndBackfill(dataptr);
              break;
              }
           default: {
//            mBluetoothGatt.readRemoteRssi();

//            Applic.scheduler.schedule(() -> { byte[] wrong={0x09}; write(0,wrong); }, 60, TimeUnit.SECONDS);
//TEST

            break;
              }
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic bluetoothGattCharacteristic, @NonNull byte[] value) {
        Log.showbytes("DexGattCallback onCharacteristicChanged UUID: " + bluetoothGattCharacteristic.getUuid().toString(), value);
        if (bluetoothGattCharacteristic.equals(charact[2])) {
            Natives.dexbackfill(dataptr, value);
            return;
        }

        if (bluetoothGattCharacteristic.equals(charact[3])) {
            getcert(value);
        } else if (bluetoothGattCharacteristic.equals(charact[1])) {
            authenticate(value);
        } else if (bluetoothGattCharacteristic.equals(charact[0])) {
            getdata(value);
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        Log.i(LOG_ID, "onCharacteristicChanged/2");
        onCharacteristicChanged(bluetoothGatt, bluetoothGattCharacteristic, bluetoothGattCharacteristic.getValue());
    }

 private void resetCerts() {
        Natives.dexResetKeys(dataptr);
        disconnect();
    }


    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        Log.i(LOG_ID, "onReadRemoteRssi(BluetoothGatt," + rssi + "," + status + (status == GATT_SUCCESS ? " SUCCESS" : " FAILURE"));
        if (status == GATT_SUCCESS) {
            readrssi = rssi;
        }
    }


    @Override
    public boolean matchDeviceName(String deviceName, String address) {
      return Natives.dexCandidate(dataptr,deviceName,address);
    }

    private void unbond() {
        var device = mActiveBluetoothDevice;
        if (device == null) {
            var bluetoothGatt = mBluetoothGatt;
            if (bluetoothGatt != null)
                device = bluetoothGatt.getDevice();
            if (device == null) {
                Log.e(LOG_ID, "device==null");
                return;
            }
        }
        try {
            Method method = device.getClass().getMethod("removeBond", (Class[]) null);
            var result = (boolean) method.invoke(device, (Object[]) null);
            if (result) {
                Log.i(LOG_ID, "Removed bond");
            }
            return;
        } catch (Exception e) {
            Log.stack(LOG_ID, "ERROR: could not remove bond", e);
        }
    }

    @Override
    public void free() {
        unbond();
        super.free();
    }

    static private final UUID ScanServiceUUID = UUID.fromString("0000febc-0000-1000-8000-00805f9b34fb");

    @Override
    public UUID getService() {
        return ScanServiceUUID;
    }

    @Override
    public void bonded() {
        final var bondstate = mActiveBluetoothDevice.getBondState();
        if (bondstate == BluetoothDevice.BOND_BONDING) {
         Log.i(LOG_ID,"bonding");
            try {
                var uristr = "android.resource://" + app.getPackageName() + "/" + R.raw.bonded;
                Uri uri = Uri.parse(uristr);
                Ringtone ring = RingtoneManager.getRingtone(app, uri);
                ring.setLooping(false);
                ring.play();
               // Applic.scheduler.schedule(ring::stop, 5, TimeUnit.SECONDS);
               disablenotification(mBluetoothGatt,charact[1]); charact[1]=null;
               disablenotification(mBluetoothGatt,charact[3]); charact[3]=null;
            } catch (Throwable th) {
                Log.stack(LOG_ID, "bonded sound", th);
            }

        } else {
            if (bondstate == BOND_BONDED) {
               Log.i(LOG_ID,"bonded");
                getdatacmd();
                if (!has_service) {
                    Applic.RunOnUiThread(() -> {
                        if (!mBluetoothGatt.discoverServices()) {
                            Log.e(LOG_ID, "bonded(): bluetoothGatt.discoverServices()  failed");
                            disconnect();
                        }
                    });
                }
            }
        }
    }

    private boolean write(int nr, byte[] data) {
        return writer(mBluetoothGatt, nr, data);
    }

    private boolean writer(BluetoothGatt mBluetoothGatt, int nr, byte[] data) {
        final var cha = charact[nr];

        if (!cha.setValue(data)) {
            Log.showbytes(LOG_ID + ": " +charuuid[nr].toString() + " cha.setValue failed", data);
            return false;
        }
        if (!mBluetoothGatt.writeCharacteristic(cha)) {
            Log.showbytes(LOG_ID + ": " +charuuid[nr].toString()  + " writeCharacteristic failed", data);
            return false;
        }
        Log.showbytes(LOG_ID + " writeCharacteristic: " + charuuid[nr].toString(), data);
        return true;
    }


    void cmd(BluetoothGatt gatt, int num) {
        Log.i(LOG_ID, "cmd(" + num + ")");
        charact[1].setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writer(gatt, 1, new byte[]{(byte) 0xA, (byte) num});
    }

 private boolean sendpacket(int num) {
        if ((packet = Natives.makeRound12bytes(dataptr, num)) == null) {
            resetCerts();
            return false;
        }
        startpacket = 0;
        charact[3].setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        sendcerts();
        return true;
    }

 private    byte[] packet = null;
 private   int startpacket = 0;
 private boolean backfilled = false;

    private  void askbackfill() {
       if(!backfilled) { //but backfilled always false
            enableNotification(mBluetoothGatt, charact[2]);
            }
       else  {
         final var cmd = Natives.getDexbackfillcmd(dataptr);
         if(cmd != null) {
           if(!write(0, cmd)) {
               Applic.scheduler.schedule(() -> { 
               if(connected)
                  write(0,cmd);}, 10, TimeUnit.MILLISECONDS);
               }
          }
       }
    }


    static private final String LOG_ID = "DexGattCallback";


    static private final byte[][] certs =
            {{(byte) 0x30, (byte) 0x82, (byte) 0x01, (byte) 0xEA, (byte) 0x30, (byte) 0x82, (byte) 0x01, (byte) 0x8F, (byte) 0xA0, (byte) 0x03, (byte) 0x02, (byte) 0x01, (byte) 0x02, (byte) 0x02, (byte) 0x14, (byte) 0x2F, (byte) 0x3C, (byte) 0x52, (byte) 0xB6, (byte) 0xEB, (byte) 0x08, (byte) 0x70, (byte) 0x10, (byte) 0x46, (byte) 0xD4, (byte) 0x5D, (byte) 0x78, (byte) 0xCE, (byte) 0x81, (byte) 0x78, (byte) 0x4C, (byte) 0x9D, (byte) 0xFE, (byte) 0x52, (byte) 0x40, (byte) 0x30, (byte) 0x0A, (byte) 0x06, (byte) 0x08, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x30, (byte) 0x13, (byte) 0x31, (byte) 0x11, (byte) 0x30, (byte) 0x0F, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x04, (byte) 0x03, (byte) 0x0C, (byte) 0x08, (byte) 0x44, (byte) 0x45, (byte) 0x58, (byte) 0x30, (byte) 0x30, (byte) 0x50, (byte) 0x47, (byte) 0x31, (byte) 0x30, (byte) 0x1E, (byte) 0x17, (byte) 0x0D, (byte) 0x32, (byte) 0x30, (byte) 0x31, (byte) 0x30, (byte) 0x33, (byte) 0x30, (byte) 0x31, (byte) 0x35, (byte) 0x35, (byte) 0x39, (byte) 0x30, (byte) 0x34, (byte) 0x5A, (byte) 0x17, (byte) 0x0D, (byte) 0x33, (byte) 0x35, (byte) 0x31, (byte) 0x30, (byte) 0x32, (byte) 0x37, (byte) 0x31, (byte) 0x35, (byte) 0x35, (byte) 0x39, (byte) 0x30, (byte) 0x34, (byte) 0x5A, (byte) 0x30, (byte) 0x13, (byte) 0x31, (byte) 0x11, (byte) 0x30, (byte) 0x0F, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x04, (byte) 0x03, (byte) 0x0C, (byte) 0x08, (byte) 0x44, (byte) 0x45, (byte) 0x58, (byte) 0x30, (byte) 0x33, (byte) 0x50, (byte) 0x47, (byte) 0x31, (byte) 0x30, (byte) 0x59, (byte) 0x30, (byte) 0x13, (byte) 0x06, (byte) 0x07, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x02, (byte) 0x01, (byte) 0x06, (byte) 0x08, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x03, (byte) 0x01, (byte) 0x07, (byte) 0x03, (byte) 0x42, (byte) 0x00, (byte) 0x04, (byte) 0xFB, (byte) 0x1A, (byte) 0xCA, (byte) 0x21, (byte) 0xD8, (byte) 0xAE, (byte) 0xEC, (byte) 0x9A, (byte) 0x4E, (byte) 0xB5, (byte) 0x1F, (byte) 0x85, (byte) 0x30, (byte) 0x49, (byte) 0x53, (byte) 0xD9, (byte) 0x77, (byte) 0xA1, (byte) 0xAD, (byte) 0x56, (byte) 0x97, (byte) 0x99, (byte) 0x25, (byte) 0x0F, (byte) 0xF8, (byte) 0x63, (byte) 0x98, (byte) 0x7F, (byte) 0x42, (byte) 0xA3, (byte) 0xCD, (byte) 0x9F, (byte) 0xA4, (byte) 0xFF, (byte) 0x57, (byte) 0x1E, (byte) 0xB5, (byte) 0x68, (byte) 0xBC, (byte) 0x6C, (byte) 0x39, (byte) 0x62, (byte) 0x77, (byte) 0xC3, (byte) 0xDC, (byte) 0xB5, (byte) 0x1D, (byte) 0xED, (byte) 0xAE, (byte) 0xE8, (byte) 0x55, (byte) 0x13, (byte) 0xC8, (byte) 0x0A, (byte) 0x5C, (byte) 0x44, (byte) 0x35, (byte) 0x53, (byte) 0x8A, (byte) 0x19, (byte) 0xF5, (byte) 0xA9, (byte) 0x63, (byte) 0x48, (byte) 0xA3, (byte) 0x81, (byte) 0xC0, (byte) 0x30, (byte) 0x81, (byte) 0xBD, (byte) 0x30, (byte) 0x0F, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x13, (byte) 0x01, (byte) 0x01, (byte) 0xFF, (byte) 0x04, (byte) 0x05, (byte) 0x30, (byte) 0x03, (byte) 0x01, (byte) 0x01, (byte) 0xFF, (byte) 0x30, (byte) 0x1F, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x23, (byte) 0x04, (byte) 0x18, (byte) 0x30, (byte) 0x16, (byte) 0x80, (byte) 0x14, (byte) 0x9E, (byte) 0x0F, (byte) 0x1E, (byte) 0x36, (byte) 0xF3, (byte) 0xF2, (byte) 0x76, (byte) 0xA7, (byte) 0x01, (byte) 0xFE, (byte) 0x8E, (byte) 0x88, (byte) 0x3A, (byte) 0x6E, (byte) 0x26, (byte) 0xA6, (byte) 0x35, (byte) 0xBD, (byte) 0x6A, (byte) 0xFC, (byte) 0x30, (byte) 0x5A, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x1F, (byte) 0x04, (byte) 0x53, (byte) 0x30, (byte) 0x51, (byte) 0x30, (byte) 0x4F, (byte) 0xA0, (byte) 0x34, (byte) 0xA0, (byte) 0x32, (byte) 0x86, (byte) 0x30, (byte) 0x68, (byte) 0x74, (byte) 0x74, (byte) 0x70, (byte) 0x3A, (byte) 0x2F, (byte) 0x2F, (byte) 0x63, (byte) 0x72, (byte) 0x6C, (byte) 0x2E, (byte) 0x64, (byte) 0x70, (byte) 0x2E, (byte) 0x73, (byte) 0x61, (byte) 0x61, (byte) 0x73, (byte) 0x2E, (byte) 0x70, (byte) 0x72, (byte) 0x69, (byte) 0x6D, (byte) 0x65, (byte) 0x6B, (byte) 0x65, (byte) 0x79, (byte) 0x2E, (byte) 0x63, (byte) 0x6F, (byte) 0x6D, (byte) 0x2F, (byte) 0x63, (byte) 0x72, (byte) 0x6C, (byte) 0x2F, (byte) 0x44, (byte) 0x45, (byte) 0x58, (byte) 0x30, (byte) 0x30, (byte) 0x50, (byte) 0x47, (byte) 0x31, (byte) 0x2E, (byte) 0x63, (byte) 0x72, (byte) 0x6C, (byte) 0xA2, (byte) 0x17, (byte) 0xA4, (byte) 0x15, (byte) 0x30, (byte) 0x13, (byte) 0x31, (byte) 0x11, (byte) 0x30, (byte) 0x0F, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x04, (byte) 0x03, (byte) 0x0C, (byte) 0x08, (byte) 0x44, (byte) 0x45, (byte) 0x58, (byte) 0x30, (byte) 0x30, (byte) 0x50, (byte) 0x47, (byte) 0x31, (byte) 0x30, (byte) 0x1D, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x0E, (byte) 0x04, (byte) 0x16, (byte) 0x04, (byte) 0x14, (byte) 0x88, (byte) 0xF6, (byte) 0x1E, (byte) 0x81, (byte) 0xBC, (byte) 0x4B, (byte) 0x17, (byte) 0xF0, (byte) 0x5C, (byte) 0x6B, (byte) 0x1B, (byte) 0xE2, (byte) 0x99, (byte) 0x1D, (byte) 0x60, (byte) 0x08, (byte) 0x7C, (byte) 0xCE, (byte) 0xDD, (byte) 0x79, (byte) 0x30, (byte) 0x0E, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x0F, (byte) 0x01, (byte) 0x01, (byte) 0xFF, (byte) 0x04, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x01, (byte) 0x86, (byte) 0x30, (byte) 0x0A, (byte) 0x06, (byte) 0x08, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x03, (byte) 0x49, (byte) 0x00, (byte) 0x30, (byte) 0x46, (byte) 0x02, (byte) 0x21, (byte) 0x00, (byte) 0xAA, (byte) 0x69, (byte) 0xCD, (byte) 0x89, (byte) 0x7E, (byte) 0xC6, (byte) 0x63, (byte) 0xAF, (byte) 0x5F, (byte) 0x9E, (byte) 0x15, (byte) 0x81, (byte) 0x87, (byte) 0xDF, (byte) 0x68, (byte) 0x51, (byte) 0xFF, (byte) 0x07, (byte) 0x56, (byte) 0xF0, (byte) 0x0C, (byte) 0x40, (byte) 0x16, (byte) 0x24, (byte) 0x56, (byte) 0x4F, (byte) 0x81, (byte) 0xA1, (byte) 0x9F, (byte) 0x5A, (byte) 0x07, (byte) 0x85, (byte) 0x02, (byte) 0x21, (byte) 0x00, (byte) 0xDA, (byte) 0xEB, (byte) 0xB9, (byte) 0xFD, (byte) 0xB1, (byte) 0x63, (byte) 0xB7, (byte) 0x31, (byte) 0xEB, (byte) 0x06, (byte) 0x61, (byte) 0xF1, (byte) 0xC0, (byte) 0xA1, (byte) 0x93, (byte) 0x28, (byte) 0x71, (byte) 0xA5, (byte) 0x0E, (byte) 0x39, (byte) 0x9A, (byte) 0xD1, (byte) 0xC6, (byte) 0xF5, (byte) 0x19, (byte) 0xEA, (byte) 0xBD, (byte) 0x4C, (byte) 0x9E, (byte) 0x7B, (byte) 0xA0, (byte) 0x13},

                    {(byte) 0x30, (byte) 0x82, (byte) 0x01, (byte) 0xCD, (byte) 0x30, (byte) 0x82, (byte) 0x01, (byte) 0x74, (byte) 0xA0, (byte) 0x03, (byte) 0x02, (byte) 0x01, (byte) 0x02, (byte) 0x02, (byte) 0x14, (byte) 0x19, (byte) 0x05, (byte) 0x2F, (byte) 0xCC, (byte) 0x17, (byte) 0x53, (byte) 0x0B, (byte) 0xFA, (byte) 0x56, (byte) 0xE4, (byte) 0x9D, (byte) 0xCA, (byte) 0xFC, (byte) 0xDA, (byte) 0xCF, (byte) 0x85, (byte) 0x3C, (byte) 0xE5, (byte) 0xBA, (byte) 0x73, (byte) 0x30, (byte) 0x0A, (byte) 0x06, (byte) 0x08, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x30, (byte) 0x13, (byte) 0x31, (byte) 0x11, (byte) 0x30, (byte) 0x0F, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x04, (byte) 0x03, (byte) 0x0C, (byte) 0x08, (byte) 0x44, (byte) 0x45, (byte) 0x58, (byte) 0x30, (byte) 0x33, (byte) 0x50, (byte) 0x47, (byte) 0x31, (byte) 0x30, (byte) 0x1E, (byte) 0x17, (byte) 0x0D, (byte) 0x32, (byte) 0x33, (byte) 0x30, (byte) 0x34, (byte) 0x31, (byte) 0x34, (byte) 0x31, (byte) 0x30, (byte) 0x32, (byte) 0x38, (byte) 0x31, (byte) 0x34, (byte) 0x5A, (byte) 0x17, (byte) 0x0D, (byte) 0x32, (byte) 0x35, (byte) 0x30, (byte) 0x34, (byte) 0x31, (byte) 0x33, (byte) 0x31, (byte) 0x30, (byte) 0x32, (byte) 0x38, (byte) 0x31, (byte) 0x33, (byte) 0x5A, (byte) 0x30, (byte) 0x3A, (byte) 0x31, (byte) 0x38, (byte) 0x30, (byte) 0x36, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x04, (byte) 0x03, (byte) 0x0C, (byte) 0x2F, (byte) 0x30, (byte) 0x31, (byte) 0x2C, (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x2C, (byte) 0x30, (byte) 0x33, (byte) 0x30, (byte) 0x30, (byte) 0x4C, (byte) 0x51, (byte) 0x45, (byte) 0x43, (byte) 0x43, (byte) 0x7A, (byte) 0x41, (byte) 0x42, (byte) 0x41, (byte) 0x77, (byte) 0x41, (byte) 0x41, (byte) 0x2C, (byte) 0x63, (byte) 0x69, (byte) 0x6F, (byte) 0x69, (byte) 0x65, (byte) 0x33, (byte) 0x56, (byte) 0x62, (byte) 0x51, (byte) 0x32, (byte) 0x68, (byte) 0x6C, (byte) 0x5A, (byte) 0x4D, (byte) 0x6A, (byte) 0x64, (byte) 0x55, (byte) 0x6D, (byte) 0x35, (byte) 0x72, (byte) 0x67, (byte) 0x41, (byte) 0x30, (byte) 0x59, (byte) 0x30, (byte) 0x13, (byte) 0x06, (byte) 0x07, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x02, (byte) 0x01, (byte) 0x06, (byte) 0x08, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x03, (byte) 0x01, (byte) 0x07, (byte) 0x03, (byte) 0x42, (byte) 0x00, (byte) 0x04, (byte) 0x51, (byte) 0x18, (byte) 0xC3, (byte) 0x5E, (byte) 0x9E, (byte) 0x41, (byte) 0xE7, (byte) 0xE0, (byte) 0x65, (byte) 0x4F, (byte) 0xEE, (byte) 0x80, (byte) 0x1C, (byte) 0x52, (byte) 0xA9, (byte) 0xC5, (byte) 0xDF, (byte) 0xC5, (byte) 0x10, (byte) 0xEF, (byte) 0x09, (byte) 0x59, (byte) 0x7D, (byte) 0x5C, (byte) 0xCA, (byte) 0x84, (byte) 0x61, (byte) 0xE4, (byte) 0xAF, (byte) 0x9C, (byte) 0x66, (byte) 0x67, (byte) 0x14, (byte) 0x83, (byte) 0x4F, (byte) 0x2B, (byte) 0xC9, (byte) 0x03, (byte) 0xF1, (byte) 0x6F, (byte) 0xAB, (byte) 0xFC, (byte) 0x45, (byte) 0x75, (byte) 0x5B, (byte) 0x01, (byte) 0x83, (byte) 0xF1, (byte) 0xA0, (byte) 0x97, (byte) 0x45, (byte) 0xCD, (byte) 0xFF, (byte) 0xCB, (byte) 0x4E, (byte) 0x2F, (byte) 0x79, (byte) 0x9E, (byte) 0x50, (byte) 0xBE, (byte) 0xD9, (byte) 0xA6, (byte) 0xB5, (byte) 0x8C, (byte) 0xA3, (byte) 0x7F, (byte) 0x30, (byte) 0x7D, (byte) 0x30, (byte) 0x0C, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x13, (byte) 0x01, (byte) 0x01, (byte) 0xFF, (byte) 0x04, (byte) 0x02, (byte) 0x30, (byte) 0x00, (byte) 0x30, (byte) 0x1F, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x23, (byte) 0x04, (byte) 0x18, (byte) 0x30, (byte) 0x16, (byte) 0x80, (byte) 0x14, (byte) 0x88, (byte) 0xF6, (byte) 0x1E, (byte) 0x81, (byte) 0xBC, (byte) 0x4B, (byte) 0x17, (byte) 0xF0, (byte) 0x5C, (byte) 0x6B, (byte) 0x1B, (byte) 0xE2, (byte) 0x99, (byte) 0x1D, (byte) 0x60, (byte) 0x08, (byte) 0x7C, (byte) 0xCE, (byte) 0xDD, (byte) 0x79, (byte) 0x30, (byte) 0x1D, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x25, (byte) 0x04, (byte) 0x16, (byte) 0x30, (byte) 0x14, (byte) 0x06, (byte) 0x08, (byte) 0x2B, (byte) 0x06, (byte) 0x01, (byte) 0x05, (byte) 0x05, (byte) 0x07, (byte) 0x03, (byte) 0x02, (byte) 0x06, (byte) 0x08, (byte) 0x2B, (byte) 0x06, (byte) 0x01, (byte) 0x05, (byte) 0x05, (byte) 0x07, (byte) 0x03, (byte) 0x01, (byte) 0x30, (byte) 0x1D, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x0E, (byte) 0x04, (byte) 0x16, (byte) 0x04, (byte) 0x14, (byte) 0xD3, (byte) 0x09, (byte) 0xE7, (byte) 0x5C, (byte) 0x07, (byte) 0x25, (byte) 0x41, (byte) 0x2D, (byte) 0x7A, (byte) 0x79, (byte) 0x22, (byte) 0xE3, (byte) 0xAA, (byte) 0xCF, (byte) 0xB2, (byte) 0x7F, (byte) 0x7E, (byte) 0xBD, (byte) 0x6B, (byte) 0xE0, (byte) 0x30, (byte) 0x0E, (byte) 0x06, (byte) 0x03, (byte) 0x55, (byte) 0x1D, (byte) 0x0F, (byte) 0x01, (byte) 0x01, (byte) 0xFF, (byte) 0x04, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x05, (byte) 0xA0, (byte) 0x30, (byte) 0x0A, (byte) 0x06, (byte) 0x08, (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0xCE, (byte) 0x3D, (byte) 0x04, (byte) 0x03, (byte) 0x02, (byte) 0x03, (byte) 0x47, (byte) 0x00, (byte) 0x30, (byte) 0x44, (byte) 0x02, (byte) 0x20, (byte) 0x48, (byte) 0xD4, (byte) 0x86, (byte) 0x8C, (byte) 0xF3, (byte) 0x93, (byte) 0xD9, (byte) 0x04, (byte) 0x41, (byte) 0x01, (byte) 0xB6, (byte) 0xF0, (byte) 0x7F, (byte) 0xD6, (byte) 0x8D, (byte) 0x7F, (byte) 0x06, (byte) 0x42, (byte) 0x80, (byte) 0x5F, (byte) 0x85, (byte) 0xDA, (byte) 0x74, (byte) 0xE2, (byte) 0xFE, (byte) 0x9D, (byte) 0xE8, (byte) 0xDD, (byte) 0x35, (byte) 0x07, (byte) 0xF0, (byte) 0x27, (byte) 0x02, (byte) 0x20, (byte) 0x1C, (byte) 0xD1, (byte) 0xBF, (byte) 0x7C, (byte) 0x6C, (byte) 0x7E, (byte) 0xDD, (byte) 0x59, (byte) 0x43, (byte) 0x5E, (byte) 0x32, (byte) 0x49, (byte) 0x25, (byte) 0xFC, (byte) 0xF0, (byte) 0xEB, (byte) 0xB3, (byte) 0xCA, (byte) 0xE2, (byte) 0x11, (byte) 0x0D, (byte) 0x79, (byte) 0x40, (byte) 0x7C, (byte) 0x77, (byte) 0xAA, (byte) 0x3B, (byte) 0x93, (byte) 0xB7, (byte) 0xBC, (byte) 0x04, (byte) 0xCB}

            };


private void resetconnect() {
   Log.i(LOG_ID,"resetconnect");
     certsize = 0x10000;
     bonded=false;
     phase=-1;
     has_service=false;
     backfilled = false;
    newcertificates=false;
      var gatt=mBluetoothGatt;
      if(nonNull(gatt)) {
          for(final BluetoothGattCharacteristic ch : charact) {
              disablenotification(gatt, ch);
          }
          }
   }
@Override
public void close() {
   resetconnect();
   super.close();
   }
}



