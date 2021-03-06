package io.github.keibai.activities.activity;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import java.io.IOException;
import java.util.Arrays;

import io.github.keibai.R;
import io.github.keibai.SaveSharedPreference;
import io.github.keibai.activities.bid.BidAdapter;
import io.github.keibai.activities.bid.BidLog;
import io.github.keibai.http.Http;
import io.github.keibai.http.HttpCallback;
import io.github.keibai.http.HttpUrl;
import io.github.keibai.models.meta.Error;
import io.github.keibai.runnable.RunnableToast;
import okhttp3.Call;

public class ActivityBidFragment extends Fragment {

    private View view;
    private Http http;

    public ActivityBidFragment() {
        // Required empty public constructor
    }

    @Override
    public void onDetach() {
        super.onDetach();

        http.close();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (http == null) {
            http = new Http(getContext());
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_activities_bid, container, false);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        fetchBidList();
    }

    @Override
    public void onStop() {
        super.onStop();

        http.close();
    }

    private void fetchBidList() {
        int userId = (int) SaveSharedPreference.getUserId(getContext());
        http.get(HttpUrl.getBidListByOwnerId(userId), new HttpCallback<BidLog[]>(BidLog[].class) {
            @Override
            public void onError(Error error) throws IOException {
                getActivity().runOnUiThread(new RunnableToast(getContext(), error.toString()));
            }

            @Override
            public void onSuccess(final BidLog[] response) throws IOException {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        BidAdapter bidAdapter = new BidAdapter(getContext(), Arrays.asList(response));
                        ListView listView = view.findViewById(R.id.activities_bid_list);
                        listView.setAdapter(bidAdapter);
                    }
                });
            }

            @Override
            public void onFailure(Call call, IOException e) {
                getActivity().runOnUiThread(new RunnableToast(getContext(), e.toString()));
            }
        });
    }
}