package com.wavesplatform.state

import com.wavesplatform.account.{Address, Alias}
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.state.reader.LeaseDetails
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.smart.script.Script
import com.wavesplatform.transaction.{AssetId, Transaction, ValidationError}
import monix.eval.Task

trait Blockchain {
  def height: Task[Int]
  def score: Task[BigInt]
  def scoreOf(blockId: ByteStr): Task[Option[BigInt]]

  def blockHeaderAndSize(height: Int): Task[Option[(BlockHeader, Int)]]
  def blockHeaderAndSize(blockId: ByteStr): Task[Option[(BlockHeader, Int)]]

  def lastBlock: Task[Option[Block]]
  def carryFee: Task[Long]
  def blockBytes(height: Int): Task[Option[Array[Byte]]]
  def blockBytes(blockId: ByteStr): Task[Option[Array[Byte]]]

  def heightOf(blockId: ByteStr): Task[Option[Int]]

  /** Returns the most recent block IDs, starting from the most recent  one */
  def lastBlockIds(howMany: Int): Task[Seq[ByteStr]]

  /** Returns a chain of blocks starting with the block with the given ID (from oldest to newest) */
  def blockIdsAfter(parentSignature: ByteStr, howMany: Int): Task[Option[Seq[ByteStr]]]

  def parent(block: Block, back: Int = 1): Task[Option[Block]]

  /** Features related */
  def approvedFeatures: Task[Map[Short, Int]]
  def activatedFeatures: Task[Map[Short, Int]]
  def featureVotes(height: Int): Task[Map[Short, Int]]

  def portfolio(a: Address): Task[Portfolio]

  def transactionInfo(id: ByteStr): Task[Option[(Int, Transaction)]]
  def transactionHeight(id: ByteStr): Task[Option[Int]]

  def addressTransactions(address: Address,
                          types: Set[Transaction.Type],
                          count: Int,
                          fromId: Option[ByteStr]): Task[Either[String, Seq[(Int, Transaction)]]]

  def containsTransaction(tx: Transaction): Task[Boolean]

  def assetDescription(id: ByteStr): Task[Option[AssetDescription]]

  def resolveAlias(a: Alias): Task[Either[ValidationError, Address]]

  def leaseDetails(leaseId: ByteStr): Task[Option[LeaseDetails]]

  def filledVolumeAndFee(orderId: ByteStr): Task[VolumeAndFee]

  /** Retrieves Waves balance snapshot in the [from, to] range (inclusive) */
  def balanceSnapshots(address: Address, from: Int, to: Int): Task[Seq[BalanceSnapshot]]

  def accountScript(address: Address): Task[Option[Script]]
  def hasScript(address: Address): Task[Boolean]

  def assetScript(id: ByteStr): Task[Option[Script]]
  def hasAssetScript(id: ByteStr): Task[Boolean]

  def accountData(acc: Address): Task[AccountDataInfo]
  def accountData(acc: Address, key: String): Task[Option[DataEntry[_]]]

  def balance(address: Address, mayBeAssetId: Option[AssetId]): Task[Long]

  def assetDistribution(assetId: ByteStr): Task[Map[Address, Long]]
  def assetDistributionAtHeight(assetId: AssetId,
                                height: Int,
                                count: Int,
                                fromAddress: Option[Address]): Task[Either[ValidationError, Map[Address, Long]]]
  def wavesDistribution(height: Int): Task[Map[Address, Long]]

  // the following methods are used exclusively by patches
  def allActiveLeases: Task[Set[LeaseTransaction]]

  /** Builds a new portfolio map by applying a partial function to all portfolios on which the function is defined.
    * @note Portfolios passed to `pf` only contain Waves and Leasing balances to improve performance */
  def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Task[Map[Address, A]]

  def append(diff: Diff, carryFee: Long, block: Block): Task[Unit]
  def rollbackTo(targetBlockId: ByteStr): Task[Either[String, Seq[Block]]]
}
