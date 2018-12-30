# -*- coding:utf-8 -*-

import Database

#将旧数据库中的数据读取后保存到csv文件中
def TransformData():
    con = Database.CreateConnection("moneyold.db")
    con.text_factory = lambda x:str(x, 'gb2312')
    cu = Database.CreateCursor(con)
    qureytext = "select Time, Name, Amount, TypeName from Income, IncomeType where IncomeType.TypeID == Income.TypeID"
    data = Database.RunQuery(cu, qureytext)
    # print(data[0])
    Database.WriteCsv("moneyold.csv", cu, data)
    print("数据转换完成")
    con.close()
    
    
if __name__ == "__main__":
    TransformData()
    