/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.calcite.rule;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.PhysicalNode;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.ignite.internal.processors.query.calcite.hint.HintDefinition;
import org.apache.ignite.internal.processors.query.calcite.rel.IgniteConvention;
import org.apache.ignite.internal.processors.query.calcite.rel.IgniteMergeJoin;
import org.apache.ignite.internal.util.typedef.F;

/**
 * Ignite Join converter.
 */
public class MergeJoinConverterRule extends AbstractIgniteJoinConverterRule {
    /** */
    public static final RelOptRule INSTANCE = new MergeJoinConverterRule();

    /**
     * Creates a converter.
     */
    public MergeJoinConverterRule() {
        super("MergeJoinConverter", HintDefinition.MERGE_JOIN);
    }

    /** {@inheritDoc} */
    @Override public boolean matchesJoin(RelOptRuleCall call) {
        LogicalJoin logicalJoin = call.rel(0);

        JoinInfo joinInfo = JoinInfo.of(logicalJoin.getLeft(), logicalJoin.getRight(), logicalJoin.getCondition());

        return !F.isEmpty(joinInfo.pairs()) && joinInfo.isEqui();
    }

    /** {@inheritDoc} */
    @Override protected PhysicalNode convert(RelOptPlanner planner, RelMetadataQuery mq, LogicalJoin rel) {
        RelOptCluster cluster = rel.getCluster();

        JoinInfo joinInfo = JoinInfo.of(rel.getLeft(), rel.getRight(), rel.getCondition());

        RelTraitSet leftInTraits = cluster.traitSetOf(IgniteConvention.INSTANCE)
            .replace(RelCollations.of(joinInfo.leftKeys));
        RelTraitSet outTraits = cluster.traitSetOf(IgniteConvention.INSTANCE);
        RelTraitSet rightInTraits = cluster.traitSetOf(IgniteConvention.INSTANCE)
            .replace(RelCollations.of(joinInfo.rightKeys));

        RelNode left = convert(rel.getLeft(), leftInTraits);
        RelNode right = convert(rel.getRight(), rightInTraits);

        return new IgniteMergeJoin(cluster, outTraits, left, right, rel.getCondition(), rel.getVariablesSet(), rel.getJoinType());
    }
}
