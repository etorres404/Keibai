package io.github.keibai.activities.auction;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Date;

import io.github.keibai.R;
import io.github.keibai.SaveSharedPreference;
import io.github.keibai.gson.BetterGson;
import io.github.keibai.http.Http;
import io.github.keibai.http.HttpCallback;
import io.github.keibai.http.HttpUrl;
import io.github.keibai.http.WebSocketBodyCallback;
import io.github.keibai.http.WebSocketConnection;
import io.github.keibai.models.Auction;
import io.github.keibai.models.Bid;
import io.github.keibai.models.Event;
import io.github.keibai.models.Good;
import io.github.keibai.models.User;
import io.github.keibai.models.meta.BodyWS;
import io.github.keibai.models.meta.Error;
import io.github.keibai.models.meta.Msg;
import okhttp3.Call;

public class DetailAuctionBidFragment extends Fragment{

    public static final String TYPE_AUCTION_SUBSCRIBE = "AuctionSubscribe";
    public static final String TYPE_AUCTION_CONNECTIONS_ONCE = "AuctionConnectionOnce";
    public static final String TYPE_AUCTION_NEW_CONNECTION = "AuctionNewConnection";
    public static final String TYPE_AUCTION_NEW_DISCONNECTION = "AuctionNewDisconnection";
    public static final String TYPE_AUCTION_BID = "AuctionBid";
    public static final String TYPE_AUCTION_BIDDED = "AuctionBidded";
    public static final String TYPE_AUCTION_START = "AuctionStart";
    public static final String TYPE_AUCTION_STARTED = "AuctionStarted";
    public static final String TYPE_AUCTION_CLOSE = "AuctionClose";
    public static final String TYPE_AUCTION_CLOSED = "AuctionClosed";

    private static final float STEP = 0.5f;

    private View view;
    private Resources res;
    private Http http;
    private WebSocketConnection wsConnection;

    private Auction auction;
    private Good good;
    private Event event;
    private User user;
    private double lastBid;
    private double minBid;
    private SparseArray<User> userMap;
    private int connections;

    private TextView auctionConnectionsText;
    private Chronometer auctionTimeChronometer;
    private TextView highestBidText;
    private TextView auctionUserCreditText;
    private EditText editTextBid;
    private TextView bidTextView;
    private SeekBar seekBarBid;
    private Button bidButton;
    private TextView bidInfoText;
    private Button startAuctionButton;
    private Button stopAuctionButton;
    private Button bidSetMinButton;
    private Button bidSet10Button;
    private Button bidSet50Button;
    private Button bidSet100Button;

    private Toast currentToast;

    public DetailAuctionBidFragment() {
        // Required empty public constructor
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        wsConnection = ((DetailAuctionActivity) getActivity()).getWsConnection();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (http == null) {
            http = new Http(getContext());
        }

        auction = SaveSharedPreference.getCurrentAuction(getContext());
        event = SaveSharedPreference.getCurrentEvent(getContext());
        user = new User() {{ id = (int) SaveSharedPreference.getUserId(getContext()); }};
        lastBid = auction.maxBid == 0.0 ? auction.startingPrice : auction.maxBid;
        minBid = lastBid + STEP;
        userMap = new SparseArray<>();
        fetchGood();

        wsSubscribe();
    }

    @Override
    public void onStop() {
        super.onStop();

        http.close();
    }

    private void wsSubscribe() {
        wsSubscribeToAuction();
        wsSubscribeToNewBids();
        wsSubscribeToAuctionStarted();
        wsSubscribeToAuctionClosed();
    }

    /* Websockets */
    private void wsSubscribeToAuction() {
        BodyWS bodySubscription = new BodyWS();
        bodySubscription.type = TYPE_AUCTION_SUBSCRIBE;
        bodySubscription.json = new BetterGson().newInstance().toJson(auction);
        wsConnection.send(bodySubscription, new WebSocketBodyCallback() {
            @Override
            public void onMessage(WebSocketConnection connection, BodyWS body) {
                wsSubscribeToNewConnectionsOnce();
            }
        });
    }

    private void wsSubscribeToNewConnectionsOnce() {
        BodyWS bodySubscribe = new BodyWS();
        bodySubscribe.type = TYPE_AUCTION_CONNECTIONS_ONCE;
        wsConnection.send(bodySubscribe, new WebSocketBodyCallback() {
            @Override
            public void onMessage(WebSocketConnection connection, BodyWS body) {
                // Returns the number of online users at a given time.
                Msg msg = new BetterGson().newInstance().fromJson(body.json, Msg.class);
                connections = Integer.valueOf(msg.msg);
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setConnectionsText();
                    }
                });
                wsSubscribeToNewConnections();
                wsSubscribeToNewDisconnections();
            }
        });
    }

    private void wsSubscribeToNewConnections() {
        wsConnection.on(TYPE_AUCTION_NEW_CONNECTION, new WebSocketBodyCallback() {
            @Override
            public void onMessage(WebSocketConnection connection, BodyWS body) {
                User user = new BetterGson().newInstance().fromJson(body.json, User.class);
                final String msg = "User " + user.name + " connected";
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(msg);
                    }
                });
                // Add user to the internal map in order to change user ID by its name
                userMap.append(user.id, user);

                // +1 to connected users.
                connections += 1;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setConnectionsText();
                    }
                });
            }
        });
    }

    private void wsSubscribeToNewDisconnections() {
        wsConnection.on(TYPE_AUCTION_NEW_DISCONNECTION, new WebSocketBodyCallback() {
            @Override
            public void onMessage(WebSocketConnection connection, BodyWS body) {
                // -1 to connected users count.
                connections -= 1;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setConnectionsText();
                    }
                });
            }
        });
    }

    private void wsSubscribeToNewBids() {
        wsConnection.on(TYPE_AUCTION_BIDDED, new WebSocketBodyCallback() {
            @Override
            public void onMessage(WebSocketConnection connection, final BodyWS body) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        final Bid newBid = new BetterGson().newInstance().fromJson(body.json, Bid.class);
                        User bidder = userMap.get(newBid.ownerId);
                        if (bidder == null) {
                            bidder = new User() {{ name = String.valueOf(newBid.ownerId); }};
                        }
                        String msg = String.format(res.getString(R.string.bid_msg_placeholder), bidder.name, newBid.amount);
                        showToast(msg);
                        lastBid = newBid.amount;
                        minBid = newBid.amount + STEP;
                        setHighestBidText((float) newBid.amount);
                        if (user.id != event.ownerId) {
                            if (user.credit < minBid + STEP) {
                                disableBidUI();
                            } else {
                                setSeekBar();
                            }
                        }
                    }
                });
            }
        });
    }

    private void wsSubscribeToAuctionStarted() {
        wsConnection.on(TYPE_AUCTION_STARTED, new WebSocketBodyCallback() {
            @Override
            public void onMessage(WebSocketConnection connection, BodyWS body) {
                try {
                    final Auction startedAuction = new BetterGson().newInstance().fromJson(body.json, Auction.class);
                    System.out.println(startedAuction);
                    // Start chronometer
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            auction = startedAuction;
                            setChronometerTime();
                            if (user.id != event.ownerId) {
                                showBidUi();
                                if (user.credit < minBid + STEP) {
                                    disableBidUI();
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        });
    }

    private void wsSubscribeToAuctionClosed() {
        wsConnection.on(TYPE_AUCTION_CLOSED, new WebSocketBodyCallback() {
            @Override
            public void onMessage(WebSocketConnection connection, BodyWS body) {
                final Auction closedAuction = new BetterGson().newInstance().fromJson(body.json, Auction.class);
                fetchWinnerAndRenderName(closedAuction.winnerId);
            }
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_detail_auction_bid, container, false);
        res = getResources();

        auctionConnectionsText = view.findViewById(R.id.auction_connections_text);
        auctionTimeChronometer = view.findViewById(R.id.auction_time_chronometer);
        highestBidText = view.findViewById(R.id.highest_bid_text);
        auctionUserCreditText = view.findViewById(R.id.auction_user_credit_text);
        editTextBid = view.findViewById(R.id.edit_text_bid);
        bidTextView = view.findViewById(R.id.bid_text_view);
        seekBarBid = view.findViewById(R.id.seek_bar_bid);
        bidButton = view.findViewById(R.id.bid_button);
        bidInfoText = view.findViewById(R.id.bid_info_text);
        startAuctionButton = view.findViewById(R.id.start_auction_button);
        stopAuctionButton = view.findViewById(R.id.stop_auction_button);
        bidSetMinButton = view.findViewById(R.id.bid_set_min_button);
        bidSet10Button = view.findViewById(R.id.bid_set_10_button);
        bidSet50Button = view.findViewById(R.id.bid_set_50_button);
        bidSet100Button = view.findViewById(R.id.bid_set_100_button);

        setHighestBidText((float) auction.maxBid);

        if (SaveSharedPreference.getUserId(getContext()) == event.ownerId) {
            // User is the owner. He/she has access to the management part of the UI
            startAuctionButton.setOnClickListener(startAuctionButtonOnClickListener);
            stopAuctionButton.setOnClickListener(stopAuctionButtonOnClickListener);
            auctionUserCreditText.setVisibility(View.INVISIBLE);
            hideBidUi();

            fetchEventAuctionsAndRenderOwnerUi();
        } else {
            // Bidder Ui
            fetchUserInfoAndRenderBidUi();
            seekBarBid.setOnSeekBarChangeListener(seekBarChangeListener);
            bidButton.setOnClickListener(bidButtonOnClickListener);
            bidSetMinButton.setOnClickListener(bidMinButtonOnClickListener);
            bidSet10Button.setOnClickListener(bid10ButtonOnClickListener);
            bidSet50Button.setOnClickListener(bid50ButtonOnClickListener);
            bidSet100Button.setOnClickListener(bid100ButtonOnClickListener);
        }

        return view;
    }

    /* Listeners */
    SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
            editTextBid.setText(String.format("%.2f", minBid + (progress * STEP)));
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

        }
    };

    View.OnClickListener startAuctionButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            // Send start auction to server
            BodyWS bodyStart = new BodyWS();
            bodyStart.type = TYPE_AUCTION_START;
            bodyStart.json = new BetterGson().newInstance().toJson(auction);
            wsConnection.send(bodyStart);

            startAuctionButton.setVisibility(View.GONE);
            stopAuctionButton.setVisibility(View.VISIBLE);
            bidInfoText.setText(res.getString(R.string.ready_stop_auction));
        }
    };

    View.OnClickListener stopAuctionButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            stopAuctionButton.setVisibility(View.GONE);
            auctionTimeChronometer.stop();
            BodyWS bodyClose = new BodyWS();
            bodyClose.type = TYPE_AUCTION_CLOSE;
            bodyClose.json = new BetterGson().newInstance().toJson(auction);
            wsConnection.send(bodyClose);
            bidInfoText.setText("");
        }
    };

    View.OnClickListener bidButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            BodyWS bodyBid = new BodyWS();
            bodyBid.type = TYPE_AUCTION_BID;

            try {
                Bid bid = new Bid();
                bid.amount = Float.parseFloat(editTextBid.getText().toString());
                bid.auctionId = auction.id;
                bid.ownerId = user.id;
                bid.goodId = good.id;
                Bid[] bids = new Bid[] {bid};
                bodyBid.json = new BetterGson().newInstance().toJson(bids);
                wsConnection.send(bodyBid, new WebSocketBodyCallback() {
                    @Override
                    public void onMessage(WebSocketConnection connection, BodyWS body) {
                        if (body.status != 200) {
                            final Error error = new BetterGson().newInstance().fromJson(body.json, Error.class);
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    showToast(error.toString());
                                }
                            });
                        }
                    }
                });
            } catch (NumberFormatException e) {
                showToast("Can not bid " + editTextBid.getText().toString());
            }
        }
    };

    View.OnClickListener bidMinButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            double value = lastBid + STEP;
            editTextBid.setText(String.valueOf(value));
        }
    };

    View.OnClickListener bid10ButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            double value = lastBid + percentageOfCurrency(auction.startingPrice, 0.1);
            editTextBid.setText(String.valueOf(value));
        }
    };

    View.OnClickListener bid50ButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            double value = lastBid + percentageOfCurrency(auction.startingPrice, 0.5);
            editTextBid.setText(String.valueOf(value));
        }
    };

    View.OnClickListener bid100ButtonOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            double value = lastBid + percentageOfCurrency(auction.startingPrice, 1);
            editTextBid.setText(String.valueOf(value));
        }
    };

    /* Fetch data */
    private void fetchUserInfoAndRenderBidUi() {
        http.get(HttpUrl.userWhoami(), new HttpCallback<User>(User.class) {

            @Override
            public void onError(final Error error) throws IOException {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(error.toString());
                    }
                });
            }

            @Override
            public void onSuccess(final User fetchedUser) throws IOException {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        user = fetchedUser;
                        renderBidUi();
                    }
                });
            }

            @Override
            public void onFailure(Call call, final IOException e) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(e.toString());
                    }
                });
            }
        });
    }

    private void fetchGood() {
        http.get(HttpUrl.getGoodListByAuctionIdUrl(auction.id), new HttpCallback<Good[]>(Good[].class) {
            @Override
            public void onError(final Error error) throws IOException {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(error.toString());
                    }
                });
            }

            @Override
            public void onSuccess(final Good[] response) throws IOException {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Only one good for the english auctions
                        good = response[0];
                    }
                });
            }

            @Override
            public void onFailure(Call call, final IOException e) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(e.toString());
                    }
                });
            }
        });
    }

    private void fetchWinnerAndRenderName(int winnerId) {
        if (winnerId == 0) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    bidInfoText.setText(R.string.auction_finished_without_winners);
                }
            });
        } else {
            http.get(HttpUrl.getUserByIdUrl(winnerId), new HttpCallback<User>(User.class) {
                @Override
                public void onError(final Error error) throws IOException {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showToast(error.toString());
                        }
                    });
                }

                @Override
                public void onSuccess(final User response) throws IOException {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            bidInfoText.setText(String.format(res.getString(R.string.auction_winner_placeholder), response.name));
                        }
                    });
                }

                @Override
                public void onFailure(Call call, final IOException e) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showToast(e.toString());
                        }
                    });
                }
            });
        }
    }

    private void fetchEventAuctionsAndRenderOwnerUi() {
        http.get(HttpUrl.getAuctionListByEventId(event.id), new HttpCallback<Auction[]>(Auction[].class) {
            @Override
            public void onError(final Error error) throws IOException {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(error.toString());
                    }
                });
            }

            @Override
            public void onSuccess(final Auction[] response) throws IOException {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Search for auction in progress
                        Auction inProgressAuction = null;
                        for (Auction a: response) {
                            if (a.status.equals(Auction.IN_PROGRESS)) {
                                inProgressAuction = a;
                                break;
                            }
                        }

                        renderOwnerUi(inProgressAuction);
                    }
                });
            }

            @Override
            public void onFailure(Call call, final IOException e) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(e.toString());
                    }
                });
            }
        });
    }

    /* Render data */
    private void renderOwnerUi(Auction inProgressAuction) {
        if (inProgressAuction != null && inProgressAuction.id != auction.id) {
            // Auction in progress is not the current auction
            String text = String.format(res.getString(R.string.in_progress_auction_placeholder), inProgressAuction.name);
            bidInfoText.setText(text);
        } else {
            // No auction in progress or auction in progress is the current auction
            switch (auction.status) {
                case Auction.ACCEPTED:
                    startAuctionButton.setVisibility(View.VISIBLE);
                    bidInfoText.setText(res.getString(R.string.ready_start_auction));
                    break;
                case Auction.IN_PROGRESS:
                    setChronometerTime();
                    stopAuctionButton.setVisibility(View.VISIBLE);
                    bidInfoText.setText(res.getString(R.string.ready_stop_auction));
                    break;
                case Auction.FINISHED:
                    fetchWinnerAndRenderName(auction.winnerId);
                    break;
                case Auction.PENDING:
                    bidInfoText.setText(res.getString(R.string.auction_should_be_accepted));
                    break;
            }
        }
    }

    public void renderBidUi() {
        setUserCreditText();

        switch (auction.status) {
            case Auction.PENDING:
                hideBidUi();
                bidInfoText.setText(res.getString(R.string.auction_not_accepted_yet));
                break;
            case Auction.ACCEPTED:
                hideBidUi();
                bidInfoText.setText(res.getString(R.string.auction_not_started_yet));
                break;
            case Auction.FINISHED:
                hideBidUi();
                bidInfoText.setText(res.getString(R.string.auction_finished));
                break;
            case Auction.IN_PROGRESS:
                setChronometerTime();
                if (user.credit < minBid + STEP) {
                    disableBidUI();
                } else {
                    setSeekBar();
                }
                break;
        }

        // Set bid buttons values.
        setBidSetButtonsValues();
    }

    private void setBidSetButtonsValues() {
        bidSetMinButton.setText(String.valueOf(STEP));
        bidSet10Button.setText(String.valueOf(percentageOfCurrency(auction.startingPrice, 0.1)));
        bidSet50Button.setText(String.valueOf(percentageOfCurrency(auction.startingPrice, 0.5)));
        bidSet100Button.setText(String.valueOf(percentageOfCurrency(auction.startingPrice, 1)));
    }

    private double percentageOfCurrency(double value, double percentage) {
        double res = value * percentage;
        double rounded = Math.floor(res * 100) / 100;
        return rounded;
    }

    /* Bidding UI utilities */
    private void setHighestBidText(float bid) {
        String text = String.format(res.getString(R.string.money_placeholder), bid);
        highestBidText.setText(text);
    }

    private void setUserCreditText() {
        String text = String.format(res.getString(R.string.auction_user_credit_placeholder), user.credit);
        auctionUserCreditText.setText(text);
    }

    private void disableBidUI() {
        showToast(res.getString(R.string.auction_user_credit_not_enough));
        seekBarBid.setProgress(100);
        editTextBid.setText(String.format("%.2f", minBid));
        editTextBid.setEnabled(false);
        seekBarBid.setEnabled(false);
        bidButton.setEnabled(false);
    }

    private void hideBidUi() {
        bidTextView.setVisibility(View.GONE);
        editTextBid.setVisibility(View.GONE);
        seekBarBid.setVisibility(View.GONE);
        bidButton.setVisibility(View.GONE);

        bidInfoText.setVisibility(View.VISIBLE);
    }

    private void showBidUi() {
        bidTextView.setVisibility(View.VISIBLE);
        editTextBid.setVisibility(View.VISIBLE);
        seekBarBid.setVisibility(View.VISIBLE);
        bidButton.setVisibility(View.VISIBLE);

        bidInfoText.setVisibility(View.GONE);

        setSeekBar();
    }

    // https://stackoverflow.com/questions/526524/android-get-time-of-chronometer-widget
    private void setChronometerTime() {
        long realtime = SystemClock.elapsedRealtime();
        Date auctionTime = auction.startTime;
        Date currentTime = new Date();
        long difference = currentTime.getTime() - auctionTime.getTime();

        auctionTimeChronometer.setBase(realtime - difference);
        auctionTimeChronometer.start();
    }

    private void showToast(String text) {
        if (currentToast == null) {
            currentToast = Toast.makeText(getContext(), text, Toast.LENGTH_LONG);
        }
        currentToast.setText(text);
        currentToast.setDuration(Toast.LENGTH_LONG);
        currentToast.show();
    }

    private void setSeekBar() {
        double range = Math.min(auction.startingPrice, user.credit - minBid);
        seekBarBid.setMax((int) (range / STEP));
        seekBarBid.setProgress(0);
        editTextBid.setText(String.format("%.2f", minBid + (seekBarBid.getProgress() * STEP)));
    }

    private void setConnectionsText() {
        String text = String.format(res.getString(R.string.connections_count), connections);
        auctionConnectionsText.setText(text);
    }
}