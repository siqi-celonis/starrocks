// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include "exec/pipeline/pipeline_builder.h"
#include "exec/pipeline/scan/balanced_chunk_buffer.h"
#include "exec/pipeline/scan/chunk_buffer_limiter.h"
#include "exec/pipeline/scan/scan_operator.h"
#include "exec/pipeline/scan/schema_scan_context.h"
#include "exec/schema_scan_node.h"
#include "gen_cpp/Types_types.h"

namespace starrocks::pipeline {

class SchemaScanOperatorFactory final : public ScanOperatorFactory {
public:
    SchemaScanOperatorFactory(int32_t id, ScanNode* schema_scan_node, size_t dop, const TPlanNode& t_node,
                              ChunkBufferLimiterPtr buffer_limiter);

    ~SchemaScanOperatorFactory() override = default;

    bool with_morsels() const override { return true; }

    Status do_prepare(RuntimeState* state) override;
    void do_close(RuntimeState* state) override;
    OperatorPtr do_create(int32_t dop, int32_t driver_sequence) override;

    BalancedChunkBuffer& get_chunk_buffer() { return _chunk_buffer; }

private:
    SchemaScanContextPtr _ctx;
    BalancedChunkBuffer _chunk_buffer;
};

class SchemaScanOperator final : public ScanOperator {
public:
    SchemaScanOperator(OperatorFactory* factory, int32_t id, int32_t driver_sequence, int32_t dop, ScanNode* scan_node,
                       SchemaScanContextPtr ctx);

    ~SchemaScanOperator() override;

    Status do_prepare(RuntimeState* state) override;
    void do_close(RuntimeState* state) override;
    ChunkSourcePtr create_chunk_source(MorselPtr morsel, int32_t chunk_source_index) override;

private:
    void attach_chunk_source(int32_t source_index) override {}
    void detach_chunk_source(int32_t source_index) override {}
    bool has_shared_chunk_source() const override { return false; }
    BalancedChunkBuffer& get_chunk_buffer() const override { return _ctx->get_chunk_buffer(); }

    workgroup::ScanSchedEntityType sched_entity_type() const override {
        return workgroup::ScanSchedEntityType::CONNECTOR;
    }

    SchemaScanContextPtr _ctx;
};
} // namespace starrocks::pipeline
