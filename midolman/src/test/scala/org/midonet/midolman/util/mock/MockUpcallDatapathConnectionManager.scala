/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.util.mock

import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

import org.midonet.midolman.PacketsEntryPoint
import org.midonet.midolman.PacketsEntryPoint.Workers
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.ManagedDatapathConnection
import org.midonet.midolman.io.MockManagedDatapathConnection
import org.midonet.midolman.io.TokenBucketPolicy
import org.midonet.midolman.io.UpcallDatapathConnectionManager
import org.midonet.midolman.toActorRef
import org.midonet.util.{TokenBucketTestRate, TokenBucket, BatchCollector}
import org.midonet.odp.{Packet, DpPort, Datapath, OvsConnectionOps}
import org.midonet.odp.protos.OvsDatapathConnection

class MockUpcallDatapathConnectionManager(config: MidolmanConfig)
        extends UpcallDatapathConnectionManager(config,
            new TokenBucketPolicy(config, new TokenBucketTestRate)) {
    protected override val log = LoggerFactory.getLogger(this.getClass)

    val conn = new MockManagedDatapathConnection()

    var upcallHandler: BatchCollector[Packet] = null

    def initialize()(implicit ec: ExecutionContext, as: ActorSystem) {
        if (upcallHandler == null) {
            upcallHandler = makeUpcallHandler(Workers(IndexedSeq(PacketsEntryPoint)))
            conn.getConnection.datapathsSetNotificationHandler(upcallHandler)
        }
    }

    override def makeConnection(name: String, tb: TokenBucket) = null

    override def stopConnection(conn: ManagedDatapathConnection) {}

    override protected def setUpcallHandler(conn: OvsDatapathConnection,
                                            w: Workers)
                                           (implicit as: ActorSystem) {
        conn.datapathsSetNotificationHandler(upcallHandler)
    }

    override def createAndHookDpPort(datapath: Datapath, port: DpPort)(
            implicit ec: ExecutionContext, as: ActorSystem)
    : Future[(DpPort,Int)] = {
        initialize()
        val ovsConOps = new OvsConnectionOps(conn.getConnection)
        ovsConOps createPort (port, datapath) map { (_, 1) }
    }

    override def deleteDpPort(datapath: Datapath, port: DpPort)(
            implicit ec: ExecutionContext, as: ActorSystem): Future[Boolean] = {
        val ovsConOps = new OvsConnectionOps(conn.getConnection)
        ovsConOps delPort(port, datapath) map { _ => true }
    }

}