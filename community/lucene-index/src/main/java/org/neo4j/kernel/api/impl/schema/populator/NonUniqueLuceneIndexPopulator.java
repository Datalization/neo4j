/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.schema.populator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.impl.schema.LuceneDocumentStructure;
import org.neo4j.kernel.api.impl.schema.LuceneSchemaIndex;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.api.index.PropertyAccessor;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.api.index.sampling.NonUniqueIndexSampler;
import org.neo4j.storageengine.api.schema.IndexSample;
import org.neo4j.unsafe.impl.internal.dragons.FeatureToggles;

public class NonUniqueLuceneIndexPopulator extends LuceneIndexPopulator
{
    private final int queueThreshold = FeatureToggles.getInteger( NonUniqueLuceneIndexPopulator.class,
            "queueThreshold", 10000 );
    private final NonUniqueIndexSampler sampler;
    private final List<NodePropertyUpdate> updates = new ArrayList<>();

    public NonUniqueLuceneIndexPopulator( LuceneSchemaIndex luceneIndex, IndexSamplingConfig samplingConfig )
    {
        super( luceneIndex );
        this.sampler = new NonUniqueIndexSampler( samplingConfig.bufferSize() );
    }

    @Override
    public void verifyDeferredConstraints( PropertyAccessor accessor ) throws IndexEntryConflictException, IOException
    {
        // no constraints to verify so do nothing
    }

    @Override
    public IndexUpdater newPopulatingUpdater( PropertyAccessor propertyAccessor ) throws IOException
    {
        return new IndexUpdater()
        {
            @Override
            public void process( NodePropertyUpdate update ) throws IOException, IndexEntryConflictException
            {
                switch ( update.getUpdateMode() )
                {
                    case ADDED:
                        // We don't look at the "before" value, so adding and changing idempotently is done the same way.
                        String encodedValue = LuceneDocumentStructure.encodedStringValue( update.getValueAfter() );
                        sampler.include( encodedValue );
                        break;
                    case CHANGED:
                        // We don't look at the "before" value, so adding and changing idempotently is done the same way.
                        String encodedValueBefore = LuceneDocumentStructure.encodedStringValue( update.getValueBefore() );
                        sampler.exclude( encodedValueBefore );
                        String encodedValueAfter = LuceneDocumentStructure.encodedStringValue( update.getValueAfter() );
                        sampler.include( encodedValueAfter );
                        break;
                    case REMOVED:
                        String removedValue = LuceneDocumentStructure.encodedStringValue( update.getValueBefore() );
                        sampler.exclude( removedValue );
                        break;
                    default:
                        throw new IllegalStateException( "Unknown update mode " + update.getUpdateMode() );
                }

                updates.add( update );
            }

            @Override
            public void close() throws IOException, IndexEntryConflictException
            {
                if ( updates.size() > queueThreshold )
                {
                    flush();
                    updates.clear();
                }

            }

            @Override
            public void remove( PrimitiveLongSet nodeIds ) throws IOException
            {
                throw new UnsupportedOperationException( "Should not remove() from populating index." );
            }
        };
    }

    @Override
    public void includeSample( NodePropertyUpdate update )
    {
        sampler.include( LuceneDocumentStructure.encodedStringValue( update.getValueAfter() ) );
    }

    @Override
    public IndexSample sampleResult()
    {
        return sampler.result();
    }

    @Override
    protected void flush() throws IOException
    {
        for ( NodePropertyUpdate update : this.updates )
        {
            long nodeId = update.getNodeId();
            switch ( update.getUpdateMode() )
            {
            case ADDED:
            case CHANGED:
                // We don't look at the "before" value, so adding and changing idempotently is done the same way.
                writer.updateDocument( LuceneDocumentStructure.newTermForChangeOrRemove( nodeId ),
                                       LuceneDocumentStructure.documentRepresentingProperty( nodeId, update.getValueAfter() ) );
                break;
            case REMOVED:
                writer.deleteDocuments( LuceneDocumentStructure.newTermForChangeOrRemove( nodeId ) );
                break;
            default:
                throw new IllegalStateException( "Unknown update mode " + update.getUpdateMode() );
            }
        }
    }
}