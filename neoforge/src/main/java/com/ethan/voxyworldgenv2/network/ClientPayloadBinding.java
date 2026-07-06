package com.ethan.voxyworldgenv2.network;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

final class ClientPayloadBinding {
    private ClientPayloadBinding() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(
                NetworkHandler.HandshakePayload.TYPE,
                NetworkHandler.HandshakePayload.CODEC,
                NetworkClientHandler::handleHandshake
        );
        registrar.playToClient(
                NetworkHandler.LODDataPayload.TYPE,
                NetworkHandler.LODDataPayload.CODEC,
                NetworkClientHandler::handleLODData
        );
        registrar.playToClient(
                NetworkHandler.ServerConfigPayload.TYPE,
                NetworkHandler.ServerConfigPayload.CODEC,
                NetworkClientHandler::handleServerConfig
        );
    }
}
