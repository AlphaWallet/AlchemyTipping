import axios from "axios";
import BigNumber from "bignumber.js";
import { nftDb } from "./Constants";
import { countUnreadOffersRecieved } from "./NftService";
import { currentEthBalance, currentOffersReceived, mynfts } from "./Store";

declare let window: any;

const ethers = window.ethers;
const ETHER_DECIMALS = 18;

export function parseUnits(amount: string, unit: number) {
  const bnAmount = new BigNumber(amount);
  try {
    return ethers.utils.parseUnits(bnAmount.toFixed(unit), unit);
  } catch (e) {
    return ethers.BigNumber.from(bnAmount.times(Math.pow(10, unit)).toFixed(0));
  }
}

export function parseEthers(amount: string) {
  return parseUnits(amount, ETHER_DECIMALS);
}

export function unitsOf(amount, decimals) {
  return ethers.utils.formatUnits(amount, decimals);
}

export function ethersOf(amount) {
  return ethers.utils.formatEther(amount);
}

export function truncate(amount: string, precision: number) {
  if (!/^(-?\d+)(.\d+)?$/.test(amount)) {
    throw new Error("Not a number!");
  }

  const dotIndex = amount.indexOf(".");
  if (dotIndex < 0) {
    return amount;
  } else {
    return amount.slice(0, dotIndex + precision + 1);
  }
}

export function trackingBlockchain(provider, currentAccount, network) {
  if (!provider || !provider.on) {
    return;
  }

  untrackingBlockchain(provider);

  provider.on("block", async () => {
    console.log(`tracking ${currentAccount} on ${network} ...`);
    trackingEthBalance(provider, currentAccount);
    mynfts.set(await nftDb.getMyNfts(network, currentAccount));
  });
}

let tid;

export function trackingUnReadOffersReceived(
  network: string,
  indentifier: string
) {
  untrackingUnReadOffersReceived();

  tid = setInterval(async () => {
    console.log(
      `tracking offers for ${indentifier} received on ${network} ...`
    );
    currentOffersReceived.set(
      await countUnreadOffersRecieved(network, indentifier)
    );
  }, 5000);
}

export function untrackingUnReadOffersReceived() {
  if (tid) {
    console.log("clear old tracking timer ...");
    clearInterval(tid);
  }
}

export function untrackingBlockchain(provider) {
  if (!provider || !provider.off) {
    return;
  }

  provider.off("block");
  console.log("untracking block events now ...");
}

async function trackingEthBalance(provider, currentAccount: string) {
  currentEthBalance.set(ethersOf(await provider.getBalance(currentAccount)));
}

export async function tokenPrice(chainId: number) {
  let response = await axios.get(
    "https://api.coingecko.com/api/v3/simple/price?ids=ethereum,matic-network&vs_currencies=usd"
  );
  let price;
  switch (chainId) {
    case 1:
    case 4:
      price = Number(response.data.ethereum.usd);
      break;
    case 137:
    case 80001:
      price = Number(response.data["matic-network"].usd);
      break;
  }
  return price;
}

export async function gasPrice() {
  return (await axios.get("https://ethgasstation.info/json/ethgasAPI.json"))
    .data;
}
