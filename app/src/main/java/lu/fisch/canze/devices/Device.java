/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package lu.fisch.canze.devices;

import java.util.ArrayList;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.actors.Message;
import lu.fisch.canze.actors.Utils;
import lu.fisch.canze.bluetooth.BluetoothManager;

/**
 * This class defines an abstract device. It has to manage the device related
 * decoding of the incoming data as well as the data flow to the device or
 * whatever is needed to "talk" to it.
 *
 * Created by robertfisch on 07.09.2015.
 */

public abstract class Device {

    /* ----------------------------------------------------------------
     * Attributes
     \ -------------------------------------------------------------- */

    /**
     * A device will "monitor" or "request" a given number of fields from
     * the connected CAN-device, so this is the list of all fields that
     * have to be read and updated.
     */
    protected ArrayList<Field> fields = new ArrayList<>();
    /**
     * Some fields will be custom, activity based
     */
    protected ArrayList<Field> customActivityFields = new ArrayList<>();
    /**
     * Some other fields will have to be queried anyway,
     * such as e.g. the speed --> safe mode driving
     */
    protected ArrayList<Field> applicationFields = new ArrayList<>();

    /**
     * The index of the actual field to query.
     * Loops over ther "fields" array
     */
    protected int fieldIndex = 0;

    protected boolean pollerActive = false;
    protected Thread pollerThread;

    /**
     * someThingWrong will be set when something goes wrong, usually a timeout.
     * most command routines just won't run when someThingWrong is set
     * someThingWrong can be reset only by calling initElm, but with toughness 100 this is the only thing it does :-)
     */
    boolean someThingWrong = false;

    /* ----------------------------------------------------------------
     * Abstract methods (to be implemented in each "real" device)
     \ -------------------------------------------------------------- */

    /**
     * A device may need some initialisation before data can be requested.
     */
    public void initConnection()
    {
        MainActivity.debug("Device: initConnection");

        if(BluetoothManager.getInstance().isConnected()) {
            MainActivity.debug("Device: BT connected");
            // make sure we only have one poller task
            if (pollerThread == null) {
                MainActivity.debug("Device: pollerThread == null");
                // post a task to the UI thread
                setPollerActive(true);

                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        // if the device has been initialised and we got an answer
                        if(initDevice(0)) {
                            while (isPollerActive()) {
                                //MainActivity.debug("Device: inside poller thread");
                                if (fields.size() == 0 || !BluetoothManager.getInstance().isConnected()) {
                                    //MainActivity.debug("Device: sleeping");
                                    try {
                                        Thread.sleep(5000);
                                    } catch (Exception e) {}
                                }
                                // query a field
                                else {
                                    //MainActivity.debug("Device: Doing next query ...");
                                    queryNextFilter();
                                }
                            }
                            // dereference the poller thread (it i stopped now anyway!)
                            MainActivity.debug("Device: Poller is done");
                            pollerThread = null;
                        }
                        else
                        {
                            MainActivity.debug("Device: no answer from device");

                            // drop the BT connexion and try again
                            (new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    // stop the BT but don't reset the device registered fields
                                    //MainActivity.getInstance().stopBluetooth(false);
                                    // reload the BT with filter registration
                                    //MainActivity.getInstance().reloadBluetooth(false);
                                    BluetoothManager.getInstance().connect();
                                }
                            })).start();
                        }
                    }
                };
                pollerThread = new Thread(r);
                // start the thread
                pollerThread.start();
            }
        }
        else
        {
            MainActivity.debug("Device: BT not connected");
            if(pollerThread!=null && pollerThread.isAlive())
            {
                setPollerActive(false);
                try {
                    MainActivity.debug("Device: joining pollerThread");
                    pollerThread.join();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    // query the device for the next filter
    protected void queryNextFilter()
    {
        if (fields.size() > 0)
        {
            try {

                Field field = null;

                // get field
                synchronized (fields) {
                    field = fields.get(fieldIndex);
                }

                MainActivity.debug("Device: queryNextFilter: " + fieldIndex + " --> " + field.getSID() + " \tSkipsCount = " + field.getSkipsCount());

                // if we got the field
                if (field != null) {

                    // only run the filter if the skipsCount is down to zero
                    boolean runFilter = (field.getSkipsCount() == 0);
                    if (runFilter)
                        // reset it to its initial value
                        field.resetSkipsCount();
                    else
                        // decrement the skipsCount
                        field.decSkipCount();

                    // get this field
                    if (runFilter) {

                        // get the data
                        String data = requestField(field);
                        // test if we got something
                        if(data!=null && !someThingWrong) {
                            process(Utils.toIntArray(data.getBytes()));
                        }

                        // reset if something went wrong ...
                        // ... but only if we are not asked to stop!
                        if (someThingWrong && BluetoothManager.getInstance().isConnected()) {
                            initDevice(1, 2);
                        }
                    }

                    // goto next filter
                    synchronized (fields) {
                        if (fields.size() == 0)
                            fieldIndex = 0;
                        else
                            fieldIndex = (fieldIndex + 1) % fields.size();
                    }
                }
                else
                    MainActivity.debug("Device: failed to get the field!");
            }
            // if any error occures, reset the fieldIndex
            catch (Exception e) {
                fieldIndex =0;
            }
        }
        else {
            // ignore if there are no fields to query
        }
    }

    /**
     * Ass the CAN bus sends a lot of free frames, the device may want
     * to apply a filter. This method should thus register or apply a
     * given filter to the hardware.
     * @param frameId   the ID of the frame to filter for
     */
    public abstract void registerFilter(int frameId);

    /**
     * Method to unregister a filter.
     * @param frameId   the ID of the frame to no longer filter on
     */
    public abstract void unregisterFilter(int frameId);

    /**
     * This method will process the passed (binary) data and then
     * return based on that and on what the internal buffer still
     * may hold a list of complete messages.
     * @param input
     * @return
     */
    protected abstract ArrayList<Message> processData(int[] input);

    public void join() throws InterruptedException{
        if(pollerThread!=null)
            pollerThread.join();
    }


    /* ----------------------------------------------------------------
     * Methods (that will be inherited by any "real" device)
     \ -------------------------------------------------------------- */

    /**
     * This method will process the passed (binary) data and notify
     * the fields (=singleton) about the incoming messages. The
     * listeners of the fields will then pass this information, via their
     * own listeners to the GUI or whoever needs to know about the changes.
     * @param input
     */
    public void process(final int[] input)
    {
        /*(new Thread(new Runnable() {
            @Override
            public void run() {
                ArrayList<Message> messages = processData(input);
                for(int i=0; i<messages.size(); i++)
                {
                    Fields.getInstance().onMessageCompleteEvent(messages.get(i));
                }
            }
        })).start();
        /**/
        ArrayList<Message> messages = processData(input);
        for(int i=0; i<messages.size(); i++)
        {
            Fields.getInstance().onMessageCompleteEvent(messages.get(i));
        }
        /**/
    }

    /**
     * This method registers the IDs of all monitored fields.
     */
    public void registerFilters()
    {
        // another thread my also access the list of monitored fields,
        // so we need to "protect" it against simultaneous changes.
        synchronized (fields) {
            for (int i = 0; i < fields.size(); i++) {
                registerFilter(fields.get(i).getId());
            }
        }
    }

    /**
     * This method unregisters all filters from the remote device
     */
    public void unregisterFilters()
    {
        synchronized (fields) {
            for (int i = 0; i < fields.size(); i++) {
                unregisterFilter(fields.get(i).getId());
            }
        }
    }

    /**
     * This method clears the list of monitored fields,
     * but only the custom ones ...
     */
    public void clearFields()
    {
        MainActivity.debug("Device: clearFields");
        synchronized (fields) {
            customActivityFields.clear();
            fields.clear();
            fields.addAll(applicationFields);
            //MainActivity.debug("cleared");
            // launch the filter clearing asynchronously
            (new Thread(new Runnable() {
                @Override
                public void run() {
                    unregisterFilters();
                }
            })).start();
        }
    }

    /**
     * A CAN message will trigger updates for all connected fields, meaning
     * any field with the same ID and the same responseID will be updated.
     * For this reason we don't need to query these fields multiple times
     * in one turn.
     * @param _field    the field to be tested
     * @return
     */
    private boolean containsField(Field _field)
    {
        for(int i=0; i<fields.size(); i++)
        {
            Field field = fields.get(i);
            if(field.getId()==_field.getId() && field.getResponseId().equals(_field.getResponseId()))
                return true;
        }
        return false;
    }

    /**
     * Method to add a field to the list of monitored field.
     * The field is also immediately registered onto the device.
     * @param field the field to be added
     */
    public void addField(final Field field)
    {
        synchronized (fields) {
            if (!containsField(field)) {
                //MainActivity.debug("reg: "+field.getSID());
                fields.add(field);
                customActivityFields.add(field);
                // launch the field registration asynchronously
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        registerFilter(field.getId());
                    }
                })).start();
            }
        }
    }

    public void addApplicationField(final Field field)
    {
        synchronized (fields) {
            if (!containsField(field)) {
                //MainActivity.debug("reg: "+field.getSID());
                fields.add(field);
                applicationFields.add(field);
                // launch the field registration asynchronously
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        registerFilter(field.getId());
                    }
                })).start();
            }
        }
    }

    /**
     * This method removes a field from the list of monitored fields
     * and unregisters the corresponding filter.
     * @param field
     */
    public void removeField(final Field field)
    {
        synchronized (fields) {
            // only remove from the custom fields
            if(customActivityFields.remove(field))
            {
                // launch the field registration asynchronously
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        unregisterFilter(field.getId());
                    }
                })).start();
            }
        }
    }

    public void removeApplicationField(final Field field)
    {
        synchronized (fields) {
            // only remove from the custom fields
            if(fields.remove(field))
            {
                applicationFields.remove(field);
                // launch the field registration asynchronously
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        unregisterFilter(field.getId());
                    }
                })).start();
            }
        }
    }

    /* ----------------------------------------------------------------
     * Methods (that will be inherited by any "real" device)
     \ -------------------------------------------------------------- */


    public void init(boolean reset) {
        // init the connection
        initConnection();

        if(reset) {
            MainActivity.debug("Device: init with reset");
            // clean all filters (just to make sure)
            clearFields();
            // register all filters (if there are any)
            registerFilters();
        }
        else
            MainActivity.debug("Device: init");
    }

    /**
     * Stop the poller thread and wait for it to be finished
     */
    public void stopAndJoin()
    {
        MainActivity.debug("Device: stopping poller");
        setPollerActive(false);
        MainActivity.debug("Device: waiting for poller to be stopped");
        try {
            if(pollerThread!=null) {
                MainActivity.debug("Device: joining thread");
                pollerThread.join();
                pollerThread=null;
            }
            else MainActivity.debug("Device: >>>>>>> pollerThread is NULL!!!");
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        MainActivity.debug("Device: poller stopped");
    }

    public boolean isPollerActive() {
        return pollerActive;
    }

    public void setPollerActive(boolean pollerActive) {
        this.pollerActive = pollerActive;
    }

    /**
     * Request a field from the device depending on the
     * type of field.
     * @param field     the field to be requested
     * @return
     */
    public String requestField(Field field)
    {
        if(field.isIsoTp()) return requestIsoTpFrame(field);
        else return requestFreeFrame(field);
    }

    /**
     * Request a free-frame type field from the device
     * @param field
     * @return
     */
    public abstract String requestFreeFrame(Field field);

    /**
     * Request an ISO-TP frame type from the device
     * @param field
     * @return
     */
    public abstract String requestIsoTpFrame(Field field);

    public abstract boolean initDevice(int toughness);

    protected abstract boolean initDevice (int toughness, int retries);
}
