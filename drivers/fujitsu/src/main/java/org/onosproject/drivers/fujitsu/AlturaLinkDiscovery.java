package org.onosproject.drivers.fujitsu;
import org.onosproject.net.behaviour.LinkDiscovery;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DefaultAnnotations.Builder;
import org.onosproject.net.link.DefaultLinkDescription;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.link.LinkDescription;
import org.onosproject.net.Port;
import org.slf4j.Logger;

import static org.onosproject.drivers.fujitsu.FujitsuVoltXmlUtility.REPORT_ALL;
import static org.slf4j.LoggerFactory.getLogger;
import org.onosproject.net.device.DeviceService;
import com.google.common.collect.Iterables;
import org.onosproject.net.driver.DriverHandler;
import org.onosproject.mastership.MastershipService;
import org.onosproject.netconf.NetconfController;
import org.onosproject.netconf.NetconfException;
import static com.google.common.base.Preconditions.checkNotNull;

import org.apache.commons.lang.StringUtils;

import org.onosproject.incubator.net.faultmanagement.alarm.AlarmService;
import org.onosproject.incubator.net.faultmanagement.alarm.Alarm;

import java.util.ArrayList;
import java.util.ListIterator;

import org.onosproject.drivers.fujitsu.AlturaMxpPuertos;

public class AlturaLinkDiscovery extends AbstractHandlerBehaviour
        implements LinkDiscovery {

    private final Logger log = getLogger(getClass());

    @Override
    public Set<LinkDescription> getLinks() {

        log.info("LinkDiscovery");
        DriverHandler handler = handler();
        NetconfController controller = handler.get(NetconfController.class);
        MastershipService mastershipService = handler.get(MastershipService.class);
        DeviceId ncDeviceId = handler.data().deviceId();
        checkNotNull(controller, "Netconf controller is null");
        String reply = null;

        if (!mastershipService.isLocalMaster(ncDeviceId)) {
            log.warn("Not master for {} Use {} to execute command",
                    ncDeviceId,
                    mastershipService.getMasterFor(ncDeviceId));
            return null;
        }

        DeviceService deviceService = this.handler().get(DeviceService.class);
        Device localdevice = deviceService.getDevice(ncDeviceId);

        /**
         * Tengo que esperar hasta que el dispositivo se conecte con ONOS.
         * Una vez se conecte, ONOS consulta por la descripcion del dispositivo. Si el mismo es OTN, puedo seguir en getLinks.
         * De lo contrario, retorno null.
         */

        Set<LinkDescription> descs = new HashSet<>();

        if ( !localdevice.type().toString().equals("OTN") ) {
            log.debug("NO SON IGUALES");
            return null;
        }

        log.info("LinkDiscovery -- Descubriendo links");

        if ( localdevice.swVersion().equals("1.0") || localdevice.swVersion().equals("2.0")  ) {

            /**
             * Pregunto al dispositivo local por sus puertos.
             */

            try {
                StringBuilder request = new StringBuilder("<mux-config xmlns=\"http://fulgor.com/ns/cli-mxp\">");
                request.append("<ports/>");
                request.append("</mux-config>");

                reply = controller
                        .getDevicesMap()
                        .get(ncDeviceId)
                        .getSession()
                        .get(request.toString(), REPORT_ALL);
            } catch (NetconfException e) {
                log.error("Cannot communicate to device {} exception {}", ncDeviceId, e);
            }



            ArrayList<AlturaMxpPuertos> lista_puertos = puertos(reply);
            DeviceId localDeviceId = this.handler().data().deviceId();

            if ( !lista_puertos.isEmpty() ) {
                ListIterator<AlturaMxpPuertos> puertosIterator = lista_puertos.listIterator();
                log.info("NO ESTA VACIO");

                aLoopName: while ( puertosIterator.hasNext() ) {

                    AlturaMxpPuertos p = puertosIterator.next();
                    log.info("El puerto es {}", p.getPuerto() );
                    log.info("El vecino es {}", p.getVecino() );
                    log.info("El puerto vecino es {}", p.getPuertoVecino() );

                    if ( (p.getVecino() == 1) || (p.getVecino() == 2) ) {

                        /**
                         * Se busca el of:x
                         */
                        com.google.common.base.Optional<Device> dev = Iterables.tryFind(
                                deviceService.getAvailableDevices(),
                                input -> input.id().toString().equals("of:000000000000000"+Integer.toString(p.getVecino())));

                        if (!dev.isPresent()) {
                            log.info("no esta el of:"+Integer.toString(p.getVecino()));
                            continue aLoopName;
                        }

                        else {

                            try {
                                StringBuilder request = new StringBuilder("<mux-state-XFP1 xmlns=\"http://fulgor.com/ns/cli-mxp\">");
                                request.append("<Presence/>");
                                request.append("</mux-state-XFP1>");

                                reply = controller
                                        .getDevicesMap()
                                        .get(ncDeviceId)
                                        .getSession()
                                        .get(request.toString(), REPORT_ALL);
                            } catch (NetconfException e) {
                                log.error("Cannot communicate to device {} exception {}", ncDeviceId, e);
                            }

                            if ( presenceOfModule(reply).equals("Yes") ) {


                                Port localPort = deviceService.getPorts(localDeviceId).get(p.getPuerto());
                                ConnectPoint local = new ConnectPoint(localDeviceId, localPort.number());

                                Device remoteDevice = dev.get();
                                Port remotePort = deviceService.getPorts(remoteDevice.id()).get(p.getPuertoVecino());
                                ConnectPoint remote = new ConnectPoint(remoteDevice.id(), remotePort.number());

                                DefaultAnnotations annotations = DefaultAnnotations.builder().set("layer", "IP").build();
                                descs.add(new DefaultLinkDescription(
                                        local, remote, Link.Type.OPTICAL, false, annotations));
                                descs.add(new DefaultLinkDescription(
                                        remote, local, Link.Type.OPTICAL, false, annotations));
                            }
                        }
                        continue aLoopName;
                    }

                    /**
                     * Se busca en los dispositivos actualmente conectados si hay alguno con un numero de serie que coincida con el indicado por el dispositivo como vecino.
                     */
                    com.google.common.base.Optional<Device> dev = Iterables.tryFind(
                            deviceService.getAvailableDevices(),
                            input -> input.serialNumber().equals( Integer.toString(p.getVecino()) ) );
                    if (!dev.isPresent()) {
                        log.info( "Device with chassis ID {} does not exist", Integer.toString(p.getVecino()) );
                        continue aLoopName;
                    }

                    /**
                     * Tengo que ver si el dispositivo local tiene alarmas referidas al enlace.
                     * Si las tiene, no armo enlace.
                     */
                    AlarmService alarmService = this.handler().get(AlarmService.class);
                    try {
                        for ( Alarm a : alarmService.getAlarms(localDeviceId)) {
                            if ( (a.id().toString().contains("RXS")) || (a.id().toString().contains("Rx LOCK ERR")) ) {
                                continue aLoopName;
                            }
                        }
                    } catch (Exception e){
                        log.info("LinkDiscovery ERROR - alarms");
                    }

                    Device remoteDevice = dev.get();
                    Port localPort = deviceService.getPorts(localDeviceId).get(p.getPuerto()); // el puerto del local con el que formo el enlace.
                    Port remotePort = deviceService.getPorts(remoteDevice.id()).get(p.getPuertoVecino()); // el puerto del vecino con el que formo el enlace.

                    ConnectPoint local = new ConnectPoint(localDeviceId, localPort.number());
                    ConnectPoint remote = new ConnectPoint(remoteDevice.id(), remotePort.number());

                    DefaultAnnotations annotations = DefaultAnnotations.builder().set("layer", "IP").build();

                    descs.add(new DefaultLinkDescription(
                            local, remote, Link.Type.OPTICAL, false, annotations));
                }
            }

        }


        return descs;
    }


    /**
     * Retrieving serial number version of device.
     * @param version the return of show version command
     * @return the serial number of the device
     */
    private ArrayList<String> serialNumber(String version) {
        log.info(version);
        String prueba = version;
        ArrayList<String> list= new ArrayList<String>();
        while (prueba.contains("deviceneighbors")) {
            String serialNumber = StringUtils.substringBetween(prueba, "<deviceneighbors>", "</deviceneighbors>");
            list.add(serialNumber);
            prueba = prueba.replaceFirst("<deviceneighbors>.*?</deviceneighbors>", "");
            log.info(prueba);
        }
        return list;
    }

    /**
     * Retrieving serial number version of device.
     * @param version the return of show version command
     * @return the serial number of the device
     */
    private String presenceOfModule(String version) {
        String presence = StringUtils.substringBetween(version, "<Presence>", "</Presence>");
        return presence;
    }

    /**
     * Retrieving ports topology of device.
     * @param parse la respuesta del dispositivo a la consulta por sus puertos.
     * @return una lista de puertos.
     */
    private ArrayList<AlturaMxpPuertos> puertos(String parse) {

        ArrayList<AlturaMxpPuertos> lista_puertos = new ArrayList<AlturaMxpPuertos>();


        while (parse.contains("ports")) {
            AlturaMxpPuertos p = new AlturaMxpPuertos();

            String info = StringUtils.substringBetween(parse, "<ports>", "</ports>"); // a esto tengo que sacar la info sobre puerto, vecino y puerto vecino

            String info_nombre_puerto = StringUtils.substringBetween(info, "<port>", "</port>");
            p.setPuerto(Integer.valueOf(info_nombre_puerto));

            String info_nombre_vecino = StringUtils.substringBetween(info, "<neighbor>", "</neighbor>");
            p.setVecino(Integer.valueOf(info_nombre_vecino));

            String info_nombre_puerto_vecino = StringUtils.substringBetween(info, "<port_neighbor>", "</port_neighbor>");
            p.setPuertoVecino(Integer.valueOf(info_nombre_puerto_vecino));

            parse = parse.replaceFirst("(?s)<ports>.*?</ports>", "");

            lista_puertos.add(p);
        }

        return lista_puertos;
    }

}
