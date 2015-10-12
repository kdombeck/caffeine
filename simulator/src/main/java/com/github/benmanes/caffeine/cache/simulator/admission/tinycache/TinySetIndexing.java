/*
 * Copyright 2015 Gilga Einziger. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.admission.tinycache;

/**
 * An implementation of TinySet's indexing method. A method to index a succinct hash table that is
 * only 2 bits from theoretical lower bound. This is only the indexing technique and it helps
 * calculate offsets in array using two indexes. chainIndex - (set bit for non empty chain/unset for
 * empty) isLastIndex (set bit for last in chain/empty bit for not last in chain). Both indexes are
 * assumed to be 64 bits, (longs) for efficiency and simplicity. The technique update the indexes
 * upon addition/removal.
 *
 * Paper link:
 * http://www.cs.technion.ac.il/users/wwwb/cgi-bin/tr-get.cgi/2015/CS/CS-2015-03.pdf
 * Presentation:
 * http://www.cs.technion.ac.il/~gilga/UCLA_and_TCL.pptx
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
public final class TinySetIndexing {
  // for performance - for functions that need to know both the start and the end of the chain.
  public static int ChainStart;
  public static int ChainEnd;

  public static int getChainStart(HashedItem fpaux, long[] chainIndex, long[] isLastIndex) {
    int requiredChainNumber = rank(chainIndex[fpaux.set], fpaux.chainId);
    int currentChainNumber = rank(isLastIndex[fpaux.set], requiredChainNumber);
    int currentOffset = requiredChainNumber;
    long tempisLastIndex = isLastIndex[fpaux.set] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempisLastIndex & 1L;
      currentOffset++;
      tempisLastIndex >>>= 1;
    }
    return currentOffset;
  }

  public static int rank(long index, int bitNum) {
    return Long.bitCount(index & (~(-1L << bitNum)));
  }

  public static int getChain(HashedItem fpaux, long[] chainIndex, long[] isLastIndex) {
    int requiredChainNumber = rank(chainIndex[fpaux.set], fpaux.chainId);
    int currentChainNumber = rank(isLastIndex[fpaux.set], requiredChainNumber);
    int currentOffset = requiredChainNumber;

    long tempisLastIndex = isLastIndex[fpaux.set] >>> requiredChainNumber;
    while (currentChainNumber < requiredChainNumber) {
      currentChainNumber += tempisLastIndex & 1L;
      currentOffset++;
      tempisLastIndex >>>= 1;
    }
    TinySetIndexing.ChainStart = currentOffset;

    while ((tempisLastIndex & 1L) == 0) {
      currentOffset++;
      tempisLastIndex >>>= 1;
    }
    TinySetIndexing.ChainEnd = currentOffset;
    return currentOffset;
  }

  public static int getChainAtOffset(HashedItem fpaux, long[] chainIndex, long[] isLastIndex,
      int offset) {
    int nonEmptyChainsToSee = rank(isLastIndex[fpaux.set], offset);
    int nonEmptyChainSeen = rank(chainIndex[fpaux.set], nonEmptyChainsToSee);
    for (int i = nonEmptyChainsToSee; i <= 64;) {
      if (TinySetIndexing.chainExist(chainIndex[fpaux.set], i)
          && (nonEmptyChainSeen == nonEmptyChainsToSee)) {
        return i;
      }
      i += Math.max(1, nonEmptyChainsToSee - nonEmptyChainSeen);
      nonEmptyChainSeen = rank(chainIndex[fpaux.set], i);

    }
    throw new RuntimeException("Cannot choose victim!");
  }

  public static boolean chainExist(long chainIndex, int chainId) {
    return (chainIndex | (1L << chainId)) == chainIndex;
  }

  public static int addItem(HashedItem fpaux, long[] chainIndex, long[] isLastIndex) {
    int offset = getChainStart(fpaux, chainIndex, isLastIndex);
    long mask = 1L << fpaux.chainId;
    isLastIndex[fpaux.set] = extendZero(isLastIndex[fpaux.set], offset);

    // if the item is new...
    if ((mask | chainIndex[fpaux.set]) != chainIndex[fpaux.set]) {
      // add new chain to IO.
      chainIndex[fpaux.set] |= mask;
      // mark item as last in isLastIndex.
      isLastIndex[fpaux.set] |= (1L << offset);
    }

    return offset;
  }

  private static long extendZero(final long isLastIndex, final int offset) {
    long constantPartMask = (1L << offset) - 1;
    return (isLastIndex & constantPartMask)
        | ((isLastIndex << 1L)
        & (~(constantPartMask))
        & (~(1L << offset)));
  }

  private static long shrinkOffset(long isLastIndex, int offset) {
    long conMask = ((1L << offset) - 1);
    return (isLastIndex & conMask) | (((~conMask) & isLastIndex) >>> 1);
  }

  public static void removeItem(HashedItem fpaux, long[] chainIndex, long[] isLastIndex) {
    int chainStart = getChainStart(fpaux, chainIndex, isLastIndex);
    // avoid an if command: either update chainIndex to the new state or keep it the way it is.
    chainIndex[fpaux.set] = (isLastIndex[fpaux.set] & (1L << chainStart)) != 0L
        ? chainIndex[fpaux.set] & ~(1L << fpaux.chainId) : chainIndex[fpaux.set];
    // update isLastIndex.
    isLastIndex[fpaux.set] = shrinkOffset(isLastIndex[fpaux.set], chainStart);
  }
}
