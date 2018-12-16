package com.wavesplatform.sql

import java.util.concurrent.ThreadLocalRandom

import com.wavesplatform.BlockGen
import com.wavesplatform.account.PrivateKeyAccount
import com.wavesplatform.block.Block
import com.wavesplatform.crypto.KeyLength
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.TestFunctionalitySettings
import com.wavesplatform.state.diffs.TransactionDiffer
import com.wavesplatform.state.{BinaryDataEntry, BooleanDataEntry, ByteStr, IntegerDataEntry, SqlDb, StringDataEntry}
import com.wavesplatform.transaction.transfer.{TransferTransactionV1, TransferTransactionV2}
import com.wavesplatform.transaction.{DataTransaction, GenesisTransaction}
import monix.execution.Scheduler.fixedPool
import monix.execution.schedulers.SchedulerService
import org.scalatest.{FreeSpec, Matchers}

class SqlStorageTest extends FreeSpec with Matchers with BlockGen {

  implicit val ec: SchedulerService = fixedPool("sql-pool", poolSize = 20, reporter = println)

  "sql db test" - {
    val db = new SqlDb(TestFunctionalitySettings.Enabled)

    "test insert blocks" in {
      val master, recipient = randomPrivateKeyAccount()
      val genesisBlock      = createGenesisBlock(master, recipient).head

      //val txDiffer = TransactionDiffer.apply(TestFunctionalitySettings.Enabled, None, genesisBlock.timestamp, 1) _

      //db.insertBlock(genesisBlock, 1, 0)
      //val savedGenesisBlock = db.lastBlock.orNull
      //genesisBlock shouldBe savedGenesisBlock

      val block = TestBlock.create(
        System.currentTimeMillis(),
        genesisBlock.reference,
        Seq(
          TransferTransactionV1
            .selfSigned(None, master, recipient, 100000000, System.currentTimeMillis(), None, 100000000, Array[Byte](4, 2, 0))
            .right
            .get)
      )

      SqlDiffs.assertDiffAndState(Seq(genesisBlock), block, state = db) { (diff, blockchain) =>
        val i = 1
      }

//      db.insertBlock(block, 2, 5)
//      val savedBlock = db.lastBlock.orNull
//      block shouldBe savedBlock
//
//      val appendedBlock = TestBlock.create(System.currentTimeMillis(), block.reference, Seq(transferV1Gen.sample.get))
//      val diff = BlockDiffer.fromBlock(TestFunctionalitySettings.Enabled, db, None, block, MiningConstraint.Unlimited).right.get._1
//      db.append(diff, , appendedBlock)
    }
  }

  private val signerA, signerB = randomPrivateKeyAccount()

  private def randomPrivateKeyAccount(): PrivateKeyAccount = {
    val seed = Array.ofDim[Byte](KeyLength)
    ThreadLocalRandom.current().nextBytes(seed)
    PrivateKeyAccount(seed)
  }

  private def createGenesisBlock(from: PrivateKeyAccount, to: PrivateKeyAccount): Seq[Block] = {
    val ts                   = System.currentTimeMillis() - 100000
    val genesisTx            = GenesisTransaction.create(from, Long.MaxValue - 1, ts).right.get
    val features: Set[Short] = Set[Short](2)

    (genesisTx +: Nil).zipWithIndex.map {
      case (x, i) =>
        val signer = if (i % 2 == 0) signerA else signerB
        TestBlock.create(signer, Seq(x), features)
    }
  }

//  "data db test" - {
//
//    val db = new SqlDb(TestFunctionalitySettings.Enabled)
//
//    "db put works" in {
//      val des = List(
//        IntegerDataEntry("ide", 3),
//        BooleanDataEntry("bde", true),
//        StringDataEntry("sde", "yolo"),
//        BinaryDataEntry("bdes", ByteStr(Array[Byte](4, 2, 0)))
//      )
//
//      val tx = DataTransaction.selfSigned(1, signerA, des, 300, System.currentTimeMillis()).right.get
//      db.insertData(tx, 1)
//    }
//
//    "transfer db test" in {
//      val t1 = TransferTransactionV1
//        .selfSigned(None, signerA, signerB.toAddress, 400, System.currentTimeMillis(), None, 300, Array[Byte](4, 2, 0))
//        .right
//        .get
//
//      val t2 = TransferTransactionV2
//        .selfSigned(2, None, signerB, signerA.toAddress, 400, System.currentTimeMillis() + 100, None, 300, Array[Byte](6, 9))
//        .right
//        .get
//
//      db.insertTransfer(t1, 1)
//      db.insertTransfer(t2, 1)
//    }
//
//    "genesis block " in {
//
//      val sig   = GenesisTransaction.generateSignature(signerA.toAddress, 300, System.currentTimeMillis())
//      val genTx = GenesisTransaction(signerA.toAddress, 300, System.currentTimeMillis(), ByteStr(sig))
//
//      db.insertGenesisTransaction(genTx, 1)
//
//    }
//
//  }

}
