/*
 * The MIT License (MIT)
 * Copyright (c) 2016 DataRank, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package com.simplymeasured.elasticsearch.plugins.tempest

import com.simplymeasured.elasticsearch.plugins.tempest.balancer.MockDecider
import com.simplymeasured.elasticsearch.plugins.tempest.balancer.ModelCluster
import com.simplymeasured.elasticsearch.plugins.tempest.balancer.ShardSizeCalculator
import org.eclipse.collections.api.map.MutableMap
import org.eclipse.collections.impl.factory.Lists
import org.eclipse.collections.impl.factory.Maps
import org.elasticsearch.Version
import org.elasticsearch.cluster.*
import org.elasticsearch.cluster.metadata.IndexMetaData
import org.elasticsearch.cluster.metadata.MetaData
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.routing.RoutingNodes
import org.elasticsearch.cluster.routing.RoutingTable
import org.elasticsearch.cluster.routing.ShardRouting
import org.elasticsearch.cluster.routing.ShardRoutingState
import org.elasticsearch.cluster.routing.allocation.AllocationService
import org.elasticsearch.cluster.routing.allocation.allocator.ShardsAllocators
import org.elasticsearch.cluster.routing.allocation.decider.ClusterRebalanceAllocationDecider
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.shard.ShardId
import org.elasticsearch.node.settings.NodeSettingsService
import org.elasticsearch.test.ESAllocationTestCase
import org.elasticsearch.test.ESIntegTestCase
import org.elasticsearch.test.ESTestCase
import org.elasticsearch.test.gateway.NoopGatewayAllocator
import org.joda.time.DateTime
import org.junit.Before
import org.junit.Test
import org.mockito.Matchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import java.security.AccessController
import java.security.PrivilegedAction

/**
 * Created by awhite on 5/1/16.
 */
class TempestShardsAllocatorTests : ESAllocationTestCase() {

    @Before
    fun setup() {
        AccessController.doPrivileged(PrivilegedAction { System.setSecurityManager(null) })
    }

    @Test
    fun testBasicBalance() {
        val settings = Settings.settingsBuilder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", 8)
                .build()

        val shardSizes = Maps.mutable.empty<String, Long>()
        val testClusterInfo = ClusterInfo(Maps.mutable.empty(), Maps.mutable.empty(), shardSizes, Maps.mutable.empty())
        val mockClusterService = mock(ClusterService::class.java)
        val mockClusterInfoService = mock(ClusterInfoService::class.java)
        val mockNodeSettingsService = mock(NodeSettingsService::class.java)
        Mockito.`when`(mockClusterInfoService.clusterInfo).thenReturn(testClusterInfo)

        val tempestShardsAllocator = TempestShardsAllocator(settings, mockNodeSettingsService, mockClusterService, mockClusterInfoService)
        val strategy = MockAllocationService(
                settings,
                randomAllocationDeciders(settings, NodeSettingsService(Settings.Builder.EMPTY_SETTINGS), getRandom()),
                ShardsAllocators(settings, NoopGatewayAllocator.INSTANCE, tempestShardsAllocator),
                mockClusterInfoService)

        var (routingTable, clusterState) = createCluster(strategy)

        routingTable.allShards().forEach { assertEquals(ShardRoutingState.STARTED, it.state()) }
        assignRandomShardSizes(routingTable, shardSizes)

        val shardSizeCalculator = ShardSizeCalculator(settings, clusterState.metaData, mockClusterInfoService.clusterInfo, routingTable)
        val modelClusterTracker = ModelClusterTracker()
        var modelCluster = ModelCluster(
                routingNodes = clusterState.routingNodes,
                shardSizeCalculator = shardSizeCalculator,
                mockDeciders = Lists.mutable.empty<MockDecider>(),
                blacklistFilter = { false },
                expungeBlacklistedNodes = false,
                random = getRandom())
        modelClusterTracker.add(modelCluster)

        routingTable = strategy.reroute(clusterState, "reroute").routingTable()
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build()


        modelCluster = ModelCluster(
                routingNodes = clusterState.routingNodes,
                shardSizeCalculator = shardSizeCalculator,
                mockDeciders = Lists.mutable.empty<MockDecider>(),
                blacklistFilter = { false },
                expungeBlacklistedNodes = false,
                random = getRandom())

        modelClusterTracker.add(modelCluster)
        println("Score: " + modelCluster.calculateBalanceScore())

        while (clusterState.routingNodes.shardsWithState(ShardRoutingState.INITIALIZING).isEmpty().not()) {
            routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes.shardsWithState(ShardRoutingState.INITIALIZING)).routingTable()
            clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build()
            modelCluster = ModelCluster(
                    routingNodes = clusterState.routingNodes,
                    shardSizeCalculator = shardSizeCalculator,
                    mockDeciders = Lists.mutable.empty<MockDecider>(),
                    blacklistFilter = { false },
                    expungeBlacklistedNodes = false,
                    random = getRandom())

            modelClusterTracker.add(modelCluster)
            println("Score: " + modelCluster.calculateBalanceScore())
        }

        println(modelClusterTracker)
    }

    protected fun createCluster(strategy: MockAllocationService): Pair<RoutingTable, ClusterState> {

        val indexes = (1..(5 + randomInt(10))).map { createRandomIndex(Integer.toHexString(it)) }
        val metaData = MetaData.builder().apply { indexes.forEach { this.put(it) } }.build()
        val routingTable = RoutingTable.builder().apply { indexes.forEach { this.addAsNew(metaData.index(it.index())) } }.build()

        val clusterState = ClusterState.builder(
                org.elasticsearch.cluster.ClusterName.DEFAULT)
                .metaData(metaData)
                .routingTable(routingTable)
                .nodes(DiscoveryNodes.builder().apply { (1..(3 + randomInt(20))).forEach { this.put(newNode("node${it}")) } })
                .build()

        return startupCluster(routingTable, clusterState, strategy)
    }

    fun createRandomIndex(id: String): IndexMetaData.Builder {
        return IndexMetaData
                .builder("index-${id}")
                .settings(Settings.settingsBuilder()
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                        .put(IndexMetaData.SETTING_CREATION_DATE, DateTime().millis))
                .numberOfShards(3 + randomInt(20))
                .numberOfReplicas(randomInt(2))
    }

    fun startupCluster(routingTable: RoutingTable, clusterState: ClusterState, strategy: AllocationService) : Pair<RoutingTable, ClusterState> {

        var resultRoutingTable: RoutingTable = routingTable
        var resultClusterState: ClusterState = clusterState

        for (attempt in 1..100) {
            resultRoutingTable = strategy.reroute(resultClusterState, "reroute").routingTable()
            resultClusterState = ClusterState.builder(resultClusterState).routingTable(resultRoutingTable).build()

            resultRoutingTable = strategy.applyStartedShards(resultClusterState, resultClusterState.routingNodes.shardsWithState(ShardRoutingState.INITIALIZING), false).routingTable()
            resultClusterState = ClusterState.builder(resultClusterState).routingTable(resultRoutingTable).build()

            if (resultRoutingTable.allShards().all { it.state() == ShardRoutingState.STARTED }) {
                return Pair(resultRoutingTable, resultClusterState)
            }
        }

        fail()
        throw RuntimeException()
    }

    private fun assignRandomShardSizes(routingTable: RoutingTable, shardSizes: MutableMap<String, Long>) {
        val shardSizeMap = Maps.mutable.empty<ShardId, Long>()

        for (shard in routingTable.allShards()) {
            val shardSize = shardSizeMap.getIfAbsentPut(shard.shardId(), { Math.exp(20.0 + randomDouble() * 5.0).toLong() })
            shardSizes.put(shardIdentifierFromRouting(shard), shardSize)
        }
    }

    fun shardIdentifierFromRouting(shardRouting: ShardRouting): String {
        return shardRouting.shardId().toString() + "[" + (if (shardRouting.primary()) "p" else "r") + "]"
    }
}