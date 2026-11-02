package com.pymoney.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.pymoney.R;
import com.pymoney.calc.MoneyFormat;
import com.pymoney.model.Entry;

import java.util.ArrayList;
import java.util.List;

/** 记录列表适配器。 */
public class EntryAdapter extends BaseAdapter {

    private final Context context;
    private final List<Entry> items = new ArrayList<>();

    public EntryAdapter(Context context) {
        this.context = context;
    }

    public void setItems(List<Entry> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public List<Entry> items() {
        return items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Entry getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_entry, parent, false);
        }
        Entry entry = getItem(position);
        TextView name = (TextView) view.findViewById(R.id.entry_name);
        TextView sub = (TextView) view.findViewById(R.id.entry_sub);
        TextView amount = (TextView) view.findViewById(R.id.entry_amount);
        name.setText(entry.name);
        String typeName = entry.typeName == null ? "未分类" : entry.typeName;
        sub.setText(MoneyFormat.formatDate(entry.time) + " · " + typeName);
        amount.setText(MoneyFormat.fenToYuanText(entry.amount));
        amount.setTextColor(context.getColor(entry.amount >= 0 ? R.color.income : R.color.expense));
        return view;
    }
}
