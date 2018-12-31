# -*- coding:utf-8 -*-

from DataBase import DataBase


# 将数据写入csv文件
import csv
def WriteCsv(csvName, cu, data):
    with open(csvName, "w") as csvfile:
        writer = csv.writer(csvfile)
        item = []
        for i in range(4):
            item.append(cu.description[i][0])
        writer.writerow(item)
        writer.writerows(data)

#将旧数据库中的数据读取后保存到csv文件中
def TransformData():
    db = DataBase("moneyold.db")
    con = db.GetConnect()
    con.text_factory = lambda x:str(x, 'gb2312')
    qureytext = "select Time, Name, Amount, TypeName from Income, IncomeType where IncomeType.TypeID == Income.TypeID"
    db.Execute(qureytext)
    data = db.GetResult()
    print(data[0])
    WriteCsv("moneyold.csv", db.GetCursor(), data)
    print("数据转换完成")
    
    
if __name__ == "__main__":
    TransformData()
    