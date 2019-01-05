# -*- coding:utf-8 -*-
# 初始化数据库，并把旧数据导入数据库
import pandas as pd
from DataBase import DataBase
import tools
import decimal

#从csv文件中读取数据存入dataform中
def ReadDataform(filename):
    pd.set_option("precision", 2)
    df = pd.read_csv(filename)
    df.round(2)
    return df
    
#将dataform中的数据读入一个数组中
def ReadData(df):
    Time = df.Time
    Name = df.Name
    #下面，要是精确数字
    Amount = df.Amount
    
    Type = df.TypeName
    return Time, Name, Amount, Type 
    
"""建立数据库，并创建两个表，第一个是收入支出表，含有ID，项目时间，项目名称，项目金额，类型ID几列，第二个是项目类型表，含有项目ID和项目名称两列"""
def InitDatabase(name):
    db = DataBase("money.db")
    createIncome = "CREATE TABLE Income(ID INTEGER PRIMARY KEY, Time DATE, Name NVARCHAR, Amount DECIMAL(7,2), TypeID INTEGER);"
    createType = "CREATE TABLE IncomeType(TypeID INTEGER PRIMARY KEY, TypeName NVARCHAR);"
    db.Execute(createIncome)
    db.Execute(createType)
    
#将csv格式的旧数据读入dataform并进行转换，主要是项目类型，因为有重复，用字典来保存
#将转换后的数据放入新的(也就是程序要使用的)数据库中
def ImportDatabase():
    df = ReadDataform("moneyold.csv")
    Time, Name, Amount, Type = ReadData(df)
    #处理type数据
    TypeID = 0
    TypeDic = {}
    for TypeItem in Type:
        if TypeDic.__contains__(TypeItem) == False:
            TypeID += 1
            TypeDic[TypeItem] = TypeID
    db = DataBase("money.db")
    #先放项目类型数据库
    sql = "INSERT INTO IncomeType VALUES( "
    for TypeItem in TypeDic:
        insertSQL = sql + str(TypeDic[TypeItem]) + " , \"" + TypeItem + "\");"
        db.Execute(insertSQL)
    # 再放收入支出表
    sql = "INSERT INTO INCOME VALUES ( "
    ID = 0
    for item in Time:
        time = Time[ID]
        name = Name[ID]
        amount = tools.Yuan2Fen(Amount[ID])
        typeID = TypeDic[Type[ID]]
        ID += 1
        insertSQL = sql + str(ID) + " , " + str(time) + " , \"" + name + "\" , " + str(amount) + " , " + str(typeID) + ");"
        db.Execute(insertSQL)
    sql = "select amount from Income"
    db.Execute(sql)
    res = db.GetResult()
    for money in res:
        money = tools.Fen2Yuan(money[0])
        print(money)
    
    
if __name__ == "__main__":
    """
    df = ReadDataform("moneyold.csv")
    print(df.columns)
    Time, Name, Amount, Type = ReadData(df)
    print(Time)
    print(Name)
    print(Amount)
    print(Type)
    """
    InitDatabase("money.db")
    """
    db = DataBase("money.db")
    query = "select name from sqlite_master where type='table'"
    db.Execute(query)
    res = db.GetResult()
    print(res)
    query = "PRAGMA table_info(Income)"
    db.Execute(query)
    res = db.GetResult()
    print(res)
    query = "PRAGMA table_info(IncomeType)"
    db.Execute(query)
    res = db.GetResult()
    print(res)
    """
    ImportDatabase()
    